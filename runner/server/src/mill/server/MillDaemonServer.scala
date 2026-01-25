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
    // Use the transport's synchronized write to avoid race conditions with RPC messages
    try {
      data.rpcTransport.write("")
      true
    } catch {
      case _: IOException => false
    }
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
      shutdownRequest = AtomicReference(Option.empty[(String, Int)]),
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
      data.shutdownRequest.set(Some((reason, exitCode)))
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

        // Run the actual command
        val (result, newStateCache) = main0(
          args = init.args.toArray,
          stateCache = stateCache,
          mainInteractive = init.interactive,
          streams = new SystemStreams(stdout, stderr, mill.api.daemon.DummyInputStream),
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
      case Some((reason, shutdownExitCode)) =>
        serverLog(
          s"handleConnection: executing deferred shutdown (reason=$reason, exitCode=$shutdownExitCode)"
        )
        stopServer(reason, shutdownExitCode)
        shutdownExitCode
      case None =>
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
    // NOTE: System.out/err.flush() calls were removed - they can cause issues when
    // stdout/stderr are redirected to a broken pipe after client disconnects

    // If this connection is being closed externally (e.g., another client was interrupted),
    // and there was no controlled shutdown, try to send a response so the client can retry.
    // If shutdownRequest is set, response was already sent via StopWithResponse.
    for {
      d <- data
      exitCode <- result
      if d.shutdownRequest.get().isEmpty // Only send if not a controlled shutdown
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
  case class DaemonServerData(
      shutdownRequest: AtomicReference[Option[(String, Int)]],
      // Store the RPC transport for synchronized writes (heartbeats + RPC messages)
      rpcTransport: MillRpcWireTransport
  )
}
