package mill.server

import mill.api.daemon.SystemStreams
import mill.client.*
import mill.client.lock.Locks
import mill.launcher.DaemonRpc
import mill.api.daemon.StopWithResponse
import mill.client.ServerLauncher.DaemonConfig
import mill.rpc.MillRpcWireTransport
import mill.server.Server.ConnectionData

import java.io.*
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration

abstract class MillDaemonServer[State](
    daemonDir: os.Path,
    acceptTimeout: FiniteDuration,
    locks: Locks,
    testLogEvenWhenServerIdWrong: Boolean = false
) extends Server[MillDaemonServer.DaemonServerData, Int](Server.Args(
      daemonDir = daemonDir,
      acceptTimeout = Some(acceptTimeout),
      locks = locks,
      testLogEvenWhenServerIdWrong = testLogEvenWhenServerIdWrong,
      bufferSize = 4 * 1024
    )) {

  def outLock: mill.client.lock.Lock
  def outFolder: os.Path

  private var stateCache: State = initialStateCache

  def initialStateCache: State

  private var lastConfig: Option[DaemonConfig] = None

  override def connectionHandlerThreadName(socket: Socket): String =
    s"MillServerActionRunner(${socket.getInetAddress}:${socket.getPort})"

  // For RPC, we don't need heartbeats - the RPC protocol handles connection state
  override def checkIfClientAlive(
      connectionData: ConnectionData,
      data: MillDaemonServer.DaemonServerData
  ): Boolean = {
    // The RPC protocol handles heartbeats via empty lines
    // We just need to check if the connection is still open
    // Use the transport's synchronized writeHeartbeat to avoid race conditions with RPC messages
    // and to properly check for errors (PrintStream swallows IOExceptions internally)
    data.rpcTransport.writeHeartbeat()
  }

  override def prepareConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer
  ): MillDaemonServer.DaemonServerData = {
    serverLog(s"prepareConnection ${connectionData.socketName}")
    val transport = MillRpcWireTransport(
      name = s"DaemonRpcServer-${connectionData.socketName}",
      serverToClient = new BufferedReader(new InputStreamReader(connectionData.clientToServer)),
      clientToServer = new PrintStream(connectionData.serverToClient, true),
      writeSynchronizer = new Object
    )
    MillDaemonServer.DaemonServerData(
      shutdownRequest = AtomicReference[MillDaemonServer.ShutdownRequest](null),
      rpcTransport = transport
    )
  }

  override def handleConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer,
      setIdle: Server.SetIdle,
      data: MillDaemonServer.DaemonServerData
  ): Int = {
    serverLog("handleConnection: starting RPC server")

    val transport = data.rpcTransport

    // Create a deferred stopServer that stores the shutdown request and throws StopWithResponse
    // to stop the RPC server loop while still sending a proper response to the client.
    val deferredStopServer: Server.StopServer = (reason, exitCode) => {
      serverLog(
        s"deferredStopServer: storing shutdown request (reason=$reason, exitCode=$exitCode)"
      )
      data.shutdownRequest.set(MillDaemonServer.ShutdownRequest(reason, exitCode))
      // Throw StopWithResponse to stop the RPC loop and send the response
      throw new StopWithResponse(DaemonRpc.RunCommandResult(exitCode))
    }

    // Track the exit code from normal command completion.
    // Initialize to exitCodeServerTerminated so interrupted clients get the right code.
    var commandExitCode = exitCodeServerTerminated

    val rpcServer = new DaemonRpcServer(
      serverName = s"MillDaemon-${connectionData.socketName}",
      transport = transport,
      setIdle = setIdle,
      writeToLocalLog = serverLog,
      runCommand = (init, _, stdout, stderr, setIdleInner, serverToClient) => {
        // Check for config changes using shared logic
        val clientConfig = ServerLauncher.DaemonConfig(
          millVersion = init.clientMillVersion,
          javaVersion = init.clientJavaVersion,
          jvmOpts = init.clientJvmOpts
        )

        lastConfig.foreach { stored =>
          val mismatchReasons = stored.checkMismatchAgainst(clientConfig)
          if (mismatchReasons.nonEmpty) {
            Server.withOutLock(
              noBuildLock = false,
              noWaitForBuildLock = false,
              out = outFolder,
              daemonDir = daemonDir,
              millActiveCommandMessage = "checking server configuration",
              streams = new SystemStreams(
                new PrintStream(mill.api.daemon.DummyOutputStream),
                new PrintStream(mill.api.daemon.DummyOutputStream),
                mill.api.daemon.DummyInputStream
              ),
              outLock = outLock,
              setIdle = _ => ()
            ) {
              mismatchReasons.foreach(reason => stderr.println(s"$reason, re-starting server"))

              // This sends RPC response and throws InterruptedException to stop the RPC loop
              deferredStopServer(
                s"config mismatch: ${mismatchReasons.mkString(", ")}",
                ClientUtil.ServerExitPleaseRetry
              )
            }
          }
        }
        lastConfig = Some(clientConfig)

        // Create an InputStream that polls the client for stdin data via RPC.
        // This allows the watch mode to detect Enter key presses.
        val rpcStdin = new MillDaemonServer.RpcStdinInputStream(serverToClient)

        // Run the actual command
        val (result, newStateCache) = main0(
          args = init.args.toArray,
          stateCache = stateCache,
          mainInteractive = init.interactive,
          streams = new SystemStreams(stdout, stderr, rpcStdin),
          env = init.env,
          setIdle = setIdleInner(_),
          userSpecifiedProperties = init.userSpecifiedProperties,
          initialSystemProperties = connectionData.initialSystemProperties,
          stopServer = deferredStopServer,
          serverToClient = serverToClient
        )

        stateCache = newStateCache
        commandExitCode = if (result) 0 else 1
        DaemonRpc.RunCommandResult(commandExitCode)
      }
    )

    serverLog("handleConnection: running RPC server")
    rpcServer.run()

    // Check for pending shutdown request and execute it
    val exitCode = data.shutdownRequest.get() match {
      case MillDaemonServer.ShutdownRequest(reason, shutdownExitCode) =>
        serverLog(
          s"handleConnection: executing deferred shutdown (reason=$reason, exitCode=$shutdownExitCode)"
        )
        stopServer(reason, shutdownExitCode)
        shutdownExitCode
      case null =>
        // Normal completion
        serverLog(s"handleConnection: RPC server finished normally, exitCode=$commandExitCode")
        commandExitCode
    }

    exitCode
  }

  override def endConnection(
      connectionData: ConnectionData,
      data: Option[MillDaemonServer.DaemonServerData],
      result: Option[Int]
  ): Unit = {
    // If this connection is being closed externally (e.g., another client was interrupted),
    // and there was no controlled shutdown, try to send a response so the client can retry.
    // If shutdownRequest is set, response was already sent via StopWithResponse.
    for {
      d <- data
      exitCode <- result
      if d.shutdownRequest.get() == null // Only send if not a controlled shutdown
    } {
      try {
        import mill.rpc.MillRpcServerToClient
        val response = MillRpcServerToClient.Response(
          Right(DaemonRpc.RunCommandResult(exitCode))
        )
        d.rpcTransport.writeSerialized(response, serverLog)
      } catch {
        case _: Exception => // Ignore errors, connection might already be broken
      }
    }

    // Close only the raw output stream, not the full transport.
    // We avoid closing the transport because:
    // 1. Closing the input stream first could send a TCP RST and lose output data
    // 2. Closing the PrintStream (vs raw stream) would cause subsequent writes to
    //    throw, breaking other connections' heartbeat checks
    try {
      connectionData.serverToClient.flush()
      connectionData.serverToClient.close()
    } catch { case _: Exception => }
  }

  def systemExit(exitCode: Int): Nothing = sys.exit(exitCode)

  def exitCodeServerTerminated: Int = ClientUtil.ServerExitPleaseRetry

  def main0(
      args: Array[String],
      stateCache: State,
      mainInteractive: Boolean,
      streams: SystemStreams,
      env: Map[String, String],
      setIdle: Boolean => Unit,
      userSpecifiedProperties: Map[String, String],
      initialSystemProperties: Map[String, String],
      stopServer: Server.StopServer,
      serverToClient: mill.rpc.MillRpcChannel[DaemonRpc.ServerToClient]
  ): (Boolean, State)
}

object MillDaemonServer {

  /** Represents a pending server shutdown request. */
  case class ShutdownRequest(reason: String, exitCode: Int)

  case class DaemonServerData(
      // Pending shutdown request, or null if none. Set when deferredStopServer is called.
      shutdownRequest: AtomicReference[ShutdownRequest],
      // Store the RPC transport for synchronized writes (heartbeats + RPC messages)
      rpcTransport: MillRpcWireTransport
  )

  /**
   * An InputStream that polls the client for stdin data via RPC.
   * Used to support "Enter to re-run" in watch mode when running in daemon mode.
   *
   * Note: This is designed for use with `lookForEnterKey` which calls `available()`
   * first, then `read()`. The polling only happens in `available()`.
   */
  class RpcStdinInputStream(
      serverToClient: mill.rpc.MillRpcChannel[DaemonRpc.ServerToClient]
  ) extends InputStream {
    private var buffer: Array[Byte] = Array.empty
    private var pos: Int = 0

    private def bufferedAvailable: Int = buffer.length - pos

    override def available(): Int = {
      if (bufferedAvailable > 0) {
        bufferedAvailable
      } else {
        // Poll the client for available stdin data.
        // Don't catch exceptions - let them propagate so the RPC loop exits
        // cleanly when the client disconnects.
        val result = serverToClient(DaemonRpc.ServerToClient.PollStdin())
        buffer = result.bytes
        pos = 0
        buffer.length
      }
    }

    override def read(): Int = {
      // Only read from buffer; caller should check available() first
      if (bufferedAvailable == 0) -1
      else {
        val b = buffer(pos) & 0xff
        pos += 1
        b
      }
    }

    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      if (len == 0) return 0
      // Only read from buffer; caller should check available() first
      val avail = bufferedAvailable
      if (avail == 0) return -1
      val toRead = math.min(len, avail)
      System.arraycopy(buffer, pos, b, off, toRead)
      pos += toRead
      toRead
    }
  }
}
