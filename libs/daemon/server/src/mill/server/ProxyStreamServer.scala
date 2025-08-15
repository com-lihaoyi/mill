package mill.server

import mill.constants.ProxyStream

import java.io.{BufferedInputStream, BufferedOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

/** [[Server]] that incorporates [[ProxyStream]] functionality. */
abstract class ProxyStreamServer(args: Server.Args) extends Server(args) { self =>

  /** Replaces [[PreHandleConnectionData]] in this class. */
  protected type PreHandleConnectionCustomData

  override protected type PreHandleConnectionData = ProxyStreamServerData

  case class ProxyStreamServerData(
      stdout: PrintStream,
      stderr: PrintStream,
      writtenExitCode: AtomicBoolean,
      customData: PreHandleConnectionCustomData
  ) {
    def writeExitCode(serverToClient: BufferedOutputStream, exitCode: Int): Unit = {
      self.writeExitCode(serverToClient, exitCode, writtenExitCode)
    }
  }

  private def writeExitCode(
      serverToClient: BufferedOutputStream,
      exitCode: Int,
      guard: AtomicBoolean
  ): Unit = {
    if (!guard.getAndSet(true)) {
      ProxyStream.sendEnd(serverToClient, exitCode)
    }
  }

  override protected def checkIfClientAlive(
      connectionData: ConnectionData,
      stopServer: Server.StopServer,
      data: PreHandleConnectionData
  ): Boolean = {
    ProxyStream.sendHeartbeat(connectionData.serverToClient)
    true
  }

  override protected def onStopServer(
      from: String,
      reason: String,
      exitCode: Int,
      connectionData: ConnectionData,
      data: Option[PreHandleConnectionData]
  ): Unit = {
    // Notify the client that the server is shutting down with the given exit code
    data match {
      case Some(data) => data.writeExitCode(connectionData.serverToClient, exitCode)
      case None => writeExitCode(connectionData.serverToClient, exitCode, new AtomicBoolean(false))
    }
  }

  /**
   * Invoked before a thread that runs [[handleConnection]] is spawned.
   */
  override final protected def preHandleConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer
  ): PreHandleConnectionData = {
    val stdout =
      new PrintStream(
        new ProxyStream.Output(connectionData.serverToClient, ProxyStream.StreamType.OUT),
        true
      )
    val stderr =
      new PrintStream(
        new ProxyStream.Output(connectionData.serverToClient, ProxyStream.StreamType.ERR),
        true
      )

    val customData = preHandleConnection(
      connectionData.socketInfo,
      connectionData.clientToServer,
      stdout,
      stderr,
      stopServer,
      connectionData.initialSystemProperties
    )

    ProxyStreamServerData(
      stdout = stdout,
      stderr = stderr,
      writtenExitCode = AtomicBoolean(false),
      customData = customData
    )
  }

  /**
   * Invoked before a thread that runs [[handleConnection]] is spawned.
   */
  protected def preHandleConnection(
      socketInfo: Server.SocketInfo,
      stdin: BufferedInputStream,
      stdout: PrintStream,
      stderr: PrintStream,
      stopServer: Server.StopServer,
      initialSystemProperties: Map[String, String]
  ): PreHandleConnectionCustomData

  /**
   * Handle a single client connection in a separate thread.
   *
   * @return the exit code to return to the client
   */
  protected def handleConnection(
      socketInfo: Server.SocketInfo,
      stdin: BufferedInputStream,
      stdout: PrintStream,
      stderr: PrintStream,
      stopServer: Server.StopServer,
      setIdle: Server.SetIdle,
      initialSystemProperties: Map[String, String],
      data: PreHandleConnectionCustomData
  ): Int

  override final protected def handleConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer,
      setIdle: Server.SetIdle,
      data: PreHandleConnectionData
  ): Unit = {
    val exitCode = handleConnection(
      connectionData.socketInfo,
      connectionData.clientToServer,
      data.stdout,
      data.stderr,
      stopServer,
      setIdle,
      connectionData.initialSystemProperties,
      data.customData
    )

    serverLog(s"connection handler finished, sending exitCode $exitCode to client")
    data.writeExitCode(connectionData.serverToClient, exitCode)
  }

  override protected def onExceptionInHandleConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer,
      data: ProxyStreamServerData,
      exception: Throwable
  ): Unit = {
    data.writeExitCode(connectionData.serverToClient, 1)
  }

  override protected def beforeSocketClose(
      connectionData: ConnectionData,
      stopServer: Server.StopServer,
      data: ProxyStreamServerData
  ): Unit = {
    try
      // Send a termination if it has not already happened
      data.writeExitCode(connectionData.serverToClient, 1)
    catch {
      case NonFatal(err) =>
        serverLog(
          s"error sending exit code 1, client seems to be dead: $err\n\n${err.getStackTrace.mkString("\n")}"
        )
    }
  }
}
