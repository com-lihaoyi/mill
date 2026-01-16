package mill.server

import mill.api.daemon.SystemStreams
import mill.client.*
import mill.client.lock.Locks
import mill.launcher.DaemonRpc
import mill.rpc.{MillRpcServerToClient, MillRpcWireTransport}
import mill.server.Server.ConnectionData

import java.io.*
import java.net.Socket
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
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
  private var lastJavaVersion = Option.empty[String]

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
      writtenExitCode = AtomicBoolean(false),
      exitCode = AtomicInteger(-1),
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

    // Create a deferred stopServer that sends the RPC response immediately, then throws
    // InterruptedException to stop the RPC server loop. We throw an exception because
    // systemExitWithReason expects to never return (Nothing type).
    val deferredStopServer: Server.StopServer = (reason, exitCode) => {
      serverLog(
        s"deferredStopServer: sending RPC response and storing shutdown request (reason=$reason, exitCode=$exitCode)"
      )
      // Send the RPC response immediately
      if (data.writtenExitCode.compareAndSet(false, true)) {
        data.exitCode.set(exitCode)
        try {
          val response = MillRpcServerToClient.Response(
            Right(DaemonRpc.RunCommandResult(exitCode))
          )
          transport.writeSerialized(response, serverLog)
        } catch {
          case _: Exception => // Ignore errors, connection might already be broken
        }
      }
      // Store the shutdown request for execution after runCommand returns
      data.shutdownRequest.set(Some((reason, exitCode)))
      // Throw InterruptedException to stop the RPC server loop (handled specially by MillRpcServer)
      throw new InterruptedException(s"Shutdown requested: $reason")
    }

    val rpcServer = new DaemonRpcServer(
      serverName = s"MillDaemon-${connectionData.socketName}",
      transport = transport,
      setIdle = setIdle,
      writeToLocalLog = serverLog,
      runCommand = (init, _, stdout, stderr, setIdleInner, serverToClient) => {
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
              stderr.println(
                s"Mill version changed (${lastMillVersion.getOrElse("<unknown>")} -> ${init.clientMillVersion}), re-starting server"
              )
            }
            if (javaVersionChanged) {
              stderr.println(
                s"Java version changed (${lastJavaVersion.getOrElse("<system>")} -> ${Option(init.clientJavaVersion).getOrElse("<system>")}), re-starting server"
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
        lastJavaVersion = Some(init.clientJavaVersion)

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
        val exitCode = if (result) 0 else 1
        data.exitCode.set(exitCode)
        data.writtenExitCode.set(true) // Mark that RPC response will be sent
        DaemonRpc.RunCommandResult(exitCode)
      }
    )

    serverLog("handleConnection: running RPC server")
    rpcServer.run()

    val exitCode = data.exitCode.get()
    serverLog(s"handleConnection: RPC server finished, exitCode=$exitCode")

    // Now that the RPC response has been sent, execute any pending shutdown request
    data.shutdownRequest.get() match {
      case Some((reason, shutdownExitCode)) =>
        serverLog(
          s"handleConnection: executing deferred shutdown (reason=$reason, exitCode=$shutdownExitCode)"
        )
        stopServer(reason, shutdownExitCode)
      case None =>
      // No shutdown requested
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
    // send an RPC response so the client doesn't see "wire broken"
    for {
      d <- data
      exitCode <- result
      transport <- d.rpcTransport.get()
      // Only send if we haven't already written an exit code (i.e., RPC hasn't completed normally)
      if d.writtenExitCode.compareAndSet(false, true)
    } {
      try {
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
      writtenExitCode: AtomicBoolean,
      exitCode: AtomicInteger,
      shutdownRequest: AtomicReference[Option[(String, Int)]],
      // Store the RPC transport so we can send a response when connection is closed externally
      rpcTransport: AtomicReference[Option[MillRpcWireTransport]]
  )
}
