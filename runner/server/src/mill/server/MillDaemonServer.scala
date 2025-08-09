package mill.server

import mill.api.daemon.SystemStreams
import mill.client.*
import mill.client.lock.{Lock, Locks}
import mill.constants.OutFiles

import java.io.*
import java.net.Socket
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/**
 * Models a long-lived server that receives requests from a client and calls a [[main0]]
 * method to run the commands in-process. Provides the command args, env variables,
 * JVM properties, wrapped input/output streams, and other metadata related to the
 * client command
 */
abstract class MillDaemonServer[State](
    daemonDir: os.Path,
    acceptTimeout: FiniteDuration,
    locks: Locks,
    testLogEvenWhenServerIdWrong: Boolean = false
) extends Server(
      daemonDir = daemonDir,
      acceptTimeout = Some(acceptTimeout),
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

  override protected def connectionHandlerThreadName(socket: Socket): String =
    s"MillServerActionRunner(${socket.getInetAddress}:${socket.getPort})"

  protected override type PreHandleConnectionData = ClientInitData

  override protected def preHandleConnection(
      socketInfo: Server.SocketInfo,
      stdin: InputStream,
      stdout: PrintStream,
      stderr: PrintStream,
      stopServer: Server.StopServer,
      initialSystemProperties: Map[String, String]
  ): ClientInitData = {
    serverLog(s"preHandleConnection $socketInfo")
    serverLog("reading client init data")
    val initData = ClientInitData.read(stdin)
    serverLog(s"read client init data: $initData")
    import initData.*

    serverLog("args " + upickle.default.write(args))
    serverLog("env " + upickle.default.write(env.asScala))
    serverLog("props " + upickle.default.write(userSpecifiedProperties.asScala))

    val millVersionChanged = lastMillVersion.exists(_ != clientMillVersion)
    val javaVersionChanged = lastJavaVersion.exists(_ != clientJavaVersion)

    if (millVersionChanged || javaVersionChanged) {
      MillDaemonServer.withOutLock(
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

        stopServer(
          s"version mismatch (millVersionChanged=$millVersionChanged, javaVersionChanged=$javaVersionChanged)",
          ClientUtil.ExitServerCodeWhenVersionMismatch()
        )
      }
    }
    lastMillVersion = Some(clientMillVersion)
    lastJavaVersion = Some(clientJavaVersion)

    initData
  }

  override protected def handleConnection(
      socketInfo: Server.SocketInfo,
      stdin: InputStream,
      stdout: PrintStream,
      stderr: PrintStream,
      stopServer: Server.StopServer,
      setIdle: Server.SetIdle,
      initialSystemProperties: Map[String, String],
      data: ClientInitData
  ): Int = {
    val (result, newStateCache) = main0(
      data.args,
      stateCache,
      data.interactive,
      new SystemStreams(stdout, stderr, stdin),
      data.env.asScala.toMap,
      setIdle(_),
      data.userSpecifiedProperties.asScala.toMap,
      initialSystemProperties,
      stopServer = stopServer
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
      stopServer: Server.StopServer
  ): (Boolean, State)
}

object MillDaemonServer {

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
