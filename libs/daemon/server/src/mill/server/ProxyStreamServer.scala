package mill.server

import mill.constants.ProxyStream
import mill.server.Server.ConnectionData

import java.io.{BufferedInputStream, BufferedOutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicBoolean

/** [[Server]] that incorporates [[ProxyStream]] functionality. */
abstract class ProxyStreamServer(args: Server.Args) extends Server(args) { self =>

  /** Replaces [[PrepareConnectionData]] in this class. */
  type PrepareConnectionCustomData

  override type PrepareConnectionData = ProxyStreamServerData

  case class ProxyStreamServerData(
      stdout: PrintStream,
      stderr: PrintStream,
      writtenExitCode: AtomicBoolean,
      customData: PrepareConnectionCustomData
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

    val customData = prepareConnection(
      connectionData.socketInfo,
      connectionData.clientToServer,
      stderr,
      stopServer
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
  def prepareConnection(
      socketInfo: Server.SocketInfo,
      stdin: BufferedInputStream,
      stderr: PrintStream,
      stopServer: Server.StopServer
  ): PrepareConnectionCustomData

  /**
   * Handle a single client connection in a separate thread.
   *
   * @return the exit code to return to the client
   */
  def handleConnection(
      stdin: BufferedInputStream,
      stdout: PrintStream,
      stderr: PrintStream,
      stopServer: Server.StopServer,
      setIdle: Server.SetIdle,
      initialSystemProperties: Map[String, String],
      data: PrepareConnectionCustomData
  ): Int

  override final def handleConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer,
      setIdle: Server.SetIdle,
      data: PrepareConnectionData
  ): Unit = {
    val exitCode = handleConnection(
      connectionData.clientToServer,
      data.stdout,
      data.stderr,
      stopServer,
      setIdle,
      connectionData.initialSystemProperties,
      data.customData
    )

    serverLog(s"connection handler finished, sending exitCode $exitCode to client")
    writeExitCode(connectionData.serverToClient, exitCode, data.writtenExitCode)
  }

  override def writeExitCode(connectionData: ConnectionData, data: ProxyStreamServerData): Unit = {
    writeExitCode(connectionData.serverToClient, 1, data.writtenExitCode)
  }
}
