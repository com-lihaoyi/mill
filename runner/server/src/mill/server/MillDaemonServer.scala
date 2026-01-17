package mill.server

import mill.api.daemon.SystemStreams
import mill.client.*
import mill.client.lock.Locks
import mill.constants.DaemonFiles
import mill.launcher.DaemonRpc
import mill.rpc.{MillRpcWireTransport, StopWithResponse}
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

  private var lastMillVersion = Option.empty[String]
  private var lastJavaVersion = Option.empty[os.Path]

  override def connectionHandlerThreadName(socket: Socket): String =
    s"MillServerActionRunner(${socket.getInetAddress}:${socket.getPort})"

  // For RPC, we don't need heartbeats - the RPC protocol handles connection state
  override def checkIfClientAlive(
      connectionData: ConnectionData,
      data: MillDaemonServer.DaemonServerData
  ): Boolean = {
    // The RPC protocol handles heartbeats via empty lines
    // We just need to check if the connection is still open
    try {
      // Write empty line as heartbeat
      connectionData.serverToClient.write('\n')
      connectionData.serverToClient.flush()
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
    MillDaemonServer.DaemonServerData(
      shutdownRequest = AtomicReference(Option.empty[(String, Int)]),
      rpcTransport = AtomicReference(Option.empty[MillRpcWireTransport])
    )
  }

  override def handleConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer,
      setIdle: Server.SetIdle,
      data: MillDaemonServer.DaemonServerData
  ): Int = {
    serverLog("handleConnection: starting RPC server")

    val transport = MillRpcWireTransport(
      name = s"DaemonRpcServer-${connectionData.socketName}",
      serverToClient = new BufferedReader(new InputStreamReader(connectionData.clientToServer)),
      clientToServer = new PrintStream(connectionData.serverToClient, true),
      writeSynchronizer = new Object
    )

    // Store the transport so endConnection can send an RPC response if needed
    data.rpcTransport.set(Some(transport))

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

    // Console log file for monitoring progress when another process is waiting
    val consoleLogFile = daemonDir / DaemonFiles.consoleLog
    val consoleLogStream = os.write.outputStream(consoleLogFile, createFolders = true)

    <<<<<<< HEAD
    // Track the exit code from normal command completion.
    // Initialize to exitCodeServerTerminated so interrupted clients get the right code.
    var commandExitCode = exitCodeServerTerminated

    =======
      >>>>>>> main
    val rpcServer = new DaemonRpcServer(
      serverName = s"MillDaemon-${connectionData.socketName}",
      transport = transport,
      setIdle = setIdle,
      writeToLocalLog = serverLog,
      runCommand = (init, _, stdout, stderr, setIdleInner, serverToClient) => {
        // Wrap stdout/stderr to also write to console log file
        val teeStdout = new mill.internal.MultiStream(stdout, consoleLogStream)
        val teeStderr = new mill.internal.MultiStream(stderr, consoleLogStream)

        // Check for version changes
        val millVersionChanged = lastMillVersion.exists(_ != init.clientMillVersion)
        val javaVersionChanged = lastJavaVersion.exists(_ != init.clientJavaVersion)

        if (millVersionChanged || javaVersionChanged) {
          Server.withOutLock(
            noBuildLock = false,
            noWaitForBuildLock = false,
            out = outFolder,
            millActiveCommandMessage = "checking server mill version and java version",
            streams = new SystemStreams(
              new PrintStream(mill.api.daemon.DummyOutputStream),
              new PrintStream(mill.api.daemon.DummyOutputStream),
              mill.api.daemon.DummyInputStream
            ),
            outLock = outLock,
            setIdle = _ => ()
          ) {
            if (millVersionChanged) {
              teeStderr.println(
                s"Mill version changed (${lastMillVersion.getOrElse("<unknown>")} -> ${init.clientMillVersion}), re-starting server"
              )
            }
            if (javaVersionChanged) {
              teeStderr.println(
                <<<<<<< HEAD
                  s"Java version changed (${lastJavaVersion.getOrElse("<system>")} -> ${init.clientJavaVersion.getOrElse("<system>")}), re-starting server"
                  =======
                  s"Java version changed (${lastJavaVersion.getOrElse("<system>")} -> ${Option(init.clientJavaVersion).getOrElse("<system>")}), re-starting server"
                  >>>>>>> main
              )
            }

            // This sends RPC response and throws InterruptedException to stop the RPC loop
            deferredStopServer(
              s"version mismatch (millVersionChanged=$millVersionChanged, javaVersionChanged=$javaVersionChanged)",
              ClientUtil.ServerExitPleaseRetry
            )
          }
        }
        lastMillVersion = Some(init.clientMillVersion)
        lastJavaVersion = init.clientJavaVersion

        // Run the actual command
        val (result, newStateCache) = main0(
          args = init.args.toArray,
          stateCache = stateCache,
          mainInteractive = init.interactive,
          streams = new SystemStreams(teeStdout, teeStderr, mill.api.daemon.DummyInputStream),
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
    try rpcServer.run()
    finally consoleLogStream.close()

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
      transport <- d.rpcTransport.get()
      if d.shutdownRequest.get().isEmpty // Only send if not a controlled shutdown
    } {
      try {
        import mill.rpc.MillRpcServerToClient
        val response = MillRpcServerToClient.Response(
          Right(DaemonRpc.RunCommandResult(exitCode))
        )
        transport.writeSerialized(response, serverLog)
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
      // Store the RPC transport so we can send a response when connection is closed externally
      rpcTransport: AtomicReference[Option[MillRpcWireTransport]]
  )
}
