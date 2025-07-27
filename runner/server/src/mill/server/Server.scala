package mill.server

import mill.api.daemon.SystemStreams
import mill.client.*
import mill.client.lock.{Lock, Locks}
import mill.constants.{InputPumper, OutFiles}

import java.io.*
import java.net.Socket
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}
import scala.util.control.NonFatal

/**
 * Models a long-lived server that receives requests from a client and calls a [[main0]]
 * method to run the commands in-process. Provides the command args, env variables,
 * JVM properties, wrapped input/output streams, and other metadata related to the
 * client command
 */
abstract class Server[State](
    daemonDir: os.Path,
    acceptTimeout: FiniteDuration,
    locks: Locks,
    testLogEvenWhenServerIdWrong: Boolean = false
) extends GenericServer(
      daemonDir = daemonDir,
      acceptTimeout = acceptTimeout,
      locks = locks,
      testLogEvenWhenServerIdWrong = testLogEvenWhenServerIdWrong
    ) {

  def outLock: mill.client.lock.Lock
  def out: os.Path

  private var stateCache: State = stateCache0

  /** Initial state. */
  def stateCache0: State

  private var lastMillVersion = Option.empty[String]
  private var lastJavaVersion = Option.empty[String]

  private def proxyInputStreamThroughPumper(in: InputStream): PipedInputStream = {
    val pipedInput = new PipedInputStream()
    val pipedOutput = new PipedOutputStream(pipedInput)
    val pumper = new InputPumper(() => in, () => pipedOutput, /* checkAvailable */ false)
    val pumperThread = new Thread(pumper, "proxyInputStreamThroughPumper")
    pumperThread.setDaemon(true)
    pumperThread.start()
    pipedInput
  }

  override protected def connectionHandlerThreadName(socket: Socket): String =
    s"MillServerActionRunner(${socket.getInetAddress}:${socket.getPort})"

  override protected def preHandleConnection(
      stdin: InputStream,
      stdout: PrintStream,
      stderr: PrintStream,
      stopServer: GenericServer.StopServer,
      initialSystemProperties: Map[String, String]
  ): ClientInitData = {
    val initData = ClientInitData.read(stdin)
    import initData.*

    serverLog("args " + upickle.default.write(args))
    serverLog("env " + upickle.default.write(env.asScala))
    serverLog("props " + upickle.default.write(userSpecifiedProperties.asScala))

    val millVersionChanged = lastMillVersion.exists(_ != clientMillVersion)
    val javaVersionChanged = lastJavaVersion.exists(_ != clientJavaVersion)

    if (millVersionChanged || javaVersionChanged) {
      Server.withOutLock(
        noBuildLock = false,
        noWaitForBuildLock = false,
        out = out,
        millActiveCommandMessage = "checking server mill version and java version",
        streams = new mill.api.daemon.SystemStreams(
          new PrintStream(mill.api.daemon.DummyOutputStream),
          new PrintStream(mill.api.daemon.DummyOutputStream),
          mill.api.daemon.DummyInputStream
        ),
        outLock = outLock
      ) {
        if (millVersionChanged) {
          stderr.println(
            s"Mill version changed (${lastMillVersion.getOrElse("<unknown>")} -> $clientMillVersion), re-starting server"
          )
        }
        if (javaVersionChanged) {
          stderr.println(
            s"Java version changed (${lastJavaVersion.getOrElse("<system>")} -> ${Option(clientJavaVersion).getOrElse("<system>")}), re-starting server"
          )
        }

        stopServer(ClientUtil.ExitServerCodeWhenVersionMismatch())
      }
    }
    lastMillVersion = Some(clientMillVersion)
    lastJavaVersion = Some(clientJavaVersion)

    initData
  }

  override protected def handleConnection(
      socketIn: InputStream,
      stdout: PrintStream,
      stderr: PrintStream,
      stopServer: GenericServer.StopServer,
      setIdle: GenericServer.SetIdle,
      initialSystemProperties: Map[String, String],
      data: ClientInitData
  ): Int = {
    // TODO review: this was previously in `preHandleConnection`, but I doubt that's a problem.
    //
    // Proxy the input stream through a pair of Piped**putStream via a pumper,
    // as the `UnixDomainSocketInputStream` we get directly from the socket does
    // not properly implement `available(): Int` and thus messes up polling logic
    // that relies on that method
    val proxiedSocketInput = proxyInputStreamThroughPumper(socketIn)

    val (result, newStateCache) = main0(
      data.args,
      stateCache,
      data.interactive,
      new SystemStreams(stdout, stderr, proxiedSocketInput),
      data.env.asScala.toMap,
      setIdle(_),
      data.userSpecifiedProperties.asScala.toMap,
      initialSystemProperties,
      systemExit = stopServer(_)
    )

    stateCache = newStateCache
    val exitCode = if (result) 0 else 1
    exitCode
  }

  def main0(
      args: Array[String],
      stateCache: State,
      mainInteractive: Boolean,
      streams: SystemStreams,
      env: Map[String, String],
      setIdle: Boolean => Unit,
      userSpecifiedProperties: Map[String, String],
      initialSystemProperties: Map[String, String],
      systemExit: Int => Nothing
  ): (Boolean, State)
}

object Server {

  def checkProcessIdFile(processIdFile: os.Path, processId: String): Option[String] = {
    Try(os.read(processIdFile)) match {
      case scala.util.Failure(_) => Some(s"processId file missing")

      case scala.util.Success(s) =>
        Option.when(s != processId) {
          s"processId file contents $s does not match processId $processId"
        }
    }

  }

  def watchProcessIdFile(
      processIdFile: os.Path,
      processId: String,
      running: () => Boolean,
      exit: String => Unit
  ): Unit = {
    os.write.over(processIdFile, processId, createFolders = true)

    val processIdThread = new Thread(
      () =>
        while (running()) {
          checkProcessIdFile(processIdFile, processId) match {
            case None => Thread.sleep(100)
            case Some(msg) => exit(msg)
          }
        },
      "Process ID Checker Thread"
    )
    processIdThread.setDaemon(true)
    processIdThread.start()
  }

  def tryLockBlock[T](lock: Lock)(block: mill.client.lock.TryLocked => T): Option[T] = {
    lock.tryLock() match {
      case null => None
      case l =>
        if (l.isLocked) {
          try Some(block(l))
          finally l.release()
        } else {
          None
        }
    }
  }

  def withOutLock[T](
      noBuildLock: Boolean,
      noWaitForBuildLock: Boolean,
      out: os.Path,
      millActiveCommandMessage: String,
      streams: SystemStreams,
      outLock: Lock
  )(t: => T): T = {
    if (noBuildLock) t
    else {
      def activeTaskString =
        try os.read(out / OutFiles.millActiveCommand)
        catch {
          // TODO review: _ changed to NonFatal
          case NonFatal(_) => "<unknown>"
        }

      def activeTaskPrefix = s"Another Mill process is running '$activeTaskString',"

      Using.resource {
        val tryLocked = outLock.tryLock()
        if (tryLocked.isLocked) tryLocked
        else if (noWaitForBuildLock) throw new Exception(s"$activeTaskPrefix failing")
        else {
          streams.err.println(s"$activeTaskPrefix waiting for it to be done...")
          outLock.lock()
        }
      } { _ =>
        os.write.over(out / OutFiles.millActiveCommand, millActiveCommandMessage)
        try t
        finally os.remove.all(out / OutFiles.millActiveCommand)
      }
    }
  }
}
