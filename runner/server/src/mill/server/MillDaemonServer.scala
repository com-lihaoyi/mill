package mill.server

import mill.api.daemon.SystemStreams
import mill.client.*
import mill.client.lock.{Lock, Locks}
import mill.constants.{OutFiles, ProxyStream}
import mill.server.Server.ConnectionData

import java.io.*
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
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
) extends Server(Server.Args(
      daemonDir = daemonDir,
      acceptTimeout = Some(acceptTimeout),
      locks = locks,
      testLogEvenWhenServerIdWrong = testLogEvenWhenServerIdWrong,
      bufferSize = 4 * 1024
    )) {
  def outLock: mill.client.lock.Lock
  def out: os.Path

  private var stateCache: State = initialStateCache

  /** Initial state. */
  def initialStateCache: State

  private var lastMillVersion = Option.empty[String]
  private var lastJavaVersion = Option.empty[String]

  override def connectionHandlerThreadName(socket: Socket): String =
    s"MillServerActionRunner(${socket.getInetAddress}:${socket.getPort})"

  override type PrepareConnectionData = DaemonServerData

  case class DaemonServerData(
      stdout: PrintStream,
      stderr: PrintStream,
      writtenExitCode: AtomicBoolean,
      customData: ClientInitData
  )

  def writeExitCode(
      serverToClient: BufferedOutputStream,
      exitCode: Int,
      guard: AtomicBoolean
  ): Unit = {
    if (!guard.getAndSet(true)) ProxyStream.sendEnd(serverToClient, exitCode)
  }

  override def checkIfClientAlive(
      connectionData: ConnectionData,
      data: PrepareConnectionData
  ): Boolean = {
    ProxyStream.sendHeartbeat(connectionData.serverToClient)
    true
  }

  override def onStopServer(
      from: String,
      reason: String,
      exitCode: Int,
      connectionData: ConnectionData,
      data: Option[PrepareConnectionData]
  ): Unit = {
    // Notify the client that the server is shutting down with the given exit code
    val writtenExitCode = data.fold(new AtomicBoolean(false))(_.writtenExitCode)
    writeExitCode(connectionData.serverToClient, exitCode, writtenExitCode)
  }

  /**
   * Invoked before a thread that runs [[handleConnection]] is spawned.
   */
  override final def prepareConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer
  ): PrepareConnectionData = {
    val stdout =
      new PrintStream(
        new ProxyStream.Output(connectionData.serverToClient, ProxyStream.OUT),
        true
      )
    val stderr =
      new PrintStream(
        new ProxyStream.Output(connectionData.serverToClient, ProxyStream.ERR),
        true
      )

    serverLog(s"preHandleConnection ${connectionData.socketInfo}")
    serverLog("reading client init data")
    val initData = ClientInitData.read(connectionData.clientToServer)
    serverLog(s"read client init data: $initData")
    import initData.*

    serverLog("args " + upickle.write(args))
    serverLog("env " + upickle.write(env.asScala))
    serverLog("props " + upickle.write(userSpecifiedProperties.asScala))

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

    DaemonServerData(stdout, stderr, AtomicBoolean(false), initData)
  }

  override final def handleConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer,
      setIdle: Server.SetIdle,
      data: PrepareConnectionData
  ): Unit = {
    val (result, newStateCache) = main0(
      data.customData.args,
      stateCache,
      data.customData.interactive,
      new SystemStreams(data.stdout, data.stderr, connectionData.clientToServer),
      data.customData.env.asScala.toMap,
      setIdle(_),
      data.customData.userSpecifiedProperties.asScala.toMap,
      connectionData.initialSystemProperties,
      stopServer = stopServer
    )

    stateCache = newStateCache
    val exitCode = if (result) 0 else 1

    serverLog(s"connection handler finished, sending exitCode $exitCode to client")
    writeExitCode(connectionData.serverToClient, exitCode, data.writtenExitCode)
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

  override def writeExitCode(connectionData: ConnectionData, data: DaemonServerData): Unit = {
    // flush before closing the socket
    System.out.flush()
    System.err.flush()

    writeExitCode(connectionData.serverToClient, 1, data.writtenExitCode)
  }
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
