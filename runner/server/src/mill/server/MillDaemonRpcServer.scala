package mill.server

import mill.api.daemon.SystemStreams
import mill.client.*
import mill.client.lock.{Lock, Locks}
import mill.constants.OutFiles.OutFiles
import mill.launcher.DaemonRpc
import mill.rpc.MillRpcWireTransport
import mill.server.Server.ConnectionData

import java.io.*
import java.net.Socket
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.FiniteDuration
import scala.util.Using
import scala.util.control.NonFatal

/**
 * RPC-based daemon server that uses the mill.rpc framework for communication
 * instead of the ProxyStream protocol.
 */
abstract class MillDaemonRpcServer[State](
    daemonDir: os.Path,
    acceptTimeout: FiniteDuration,
    locks: Locks,
    testLogEvenWhenServerIdWrong: Boolean = false
) extends Server[MillDaemonRpcServer.DaemonServerData, Int](Server.Args(
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
      data: MillDaemonRpcServer.DaemonServerData
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
  ): MillDaemonRpcServer.DaemonServerData = {
    mill.constants.DebugLog.println(s"MillDaemonRpcServer.prepareConnection: ${connectionData.socketName}")
    serverLog(s"prepareConnection ${connectionData.socketName}")

    // Just return minimal data - RPC will handle initialization
    MillDaemonRpcServer.DaemonServerData(
      writtenExitCode = AtomicBoolean(false),
      exitCode = AtomicInteger(-1)
    )
  }

  override def handleConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer,
      setIdle: Server.SetIdle,
      data: MillDaemonRpcServer.DaemonServerData
  ): Int = {
    mill.constants.DebugLog.println(s"MillDaemonRpcServer.handleConnection: starting RPC server")
    serverLog("handleConnection: starting RPC server")

    // Create the wire transport for RPC
    val transport = MillRpcWireTransport(
      name = s"DaemonRpcServer-${connectionData.socketName}",
      serverToClient = new BufferedReader(new InputStreamReader(connectionData.clientToServer)),
      clientToServer = new PrintStream(connectionData.serverToClient, true),
      writeSynchronizer = new Object
    )

    mill.constants.DebugLog.println(s"MillDaemonRpcServer.handleConnection: transport created")

    // Create the RPC server
    val rpcServer = new DaemonRpcServer(
      serverName = s"MillDaemon-${connectionData.socketName}",
      transport = transport,
      setIdle = setIdle,
      writeToLocalLog = serverLog,
      runCommand = (init, req, stdout, stderr, setIdleInner, serverToClient) => {
        mill.constants.DebugLog.println(s"MillDaemonRpcServer.runCommand: $init")

        // Check for version changes
        val millVersionChanged = lastMillVersion.exists(_ != init.clientMillVersion)
        val javaVersionChanged = lastJavaVersion.exists(_ != init.clientJavaVersion)

        if (millVersionChanged || javaVersionChanged) {
          MillDaemonServer.withOutLock(
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

            stopServer(
              s"version mismatch (millVersionChanged=$millVersionChanged, javaVersionChanged=$javaVersionChanged)",
              ClientUtil.ServerExitPleaseRetry()
            )
          }
        }
        lastMillVersion = Some(init.clientMillVersion)
        lastJavaVersion = Some(init.clientJavaVersion)

        // Run the actual command
        val (result, newStateCache) = main0(
          args = init.args,
          stateCache = stateCache,
          mainInteractive = init.interactive,
          streams = new SystemStreams(stdout, stderr, mill.api.daemon.DummyInputStream),
          env = init.env,
          setIdle = setIdleInner(_),
          userSpecifiedProperties = init.userSpecifiedProperties,
          initialSystemProperties = connectionData.initialSystemProperties,
          stopServer = stopServer,
          serverToClient = serverToClient
        )

        stateCache = newStateCache
        val exitCode = if (result) 0 else 1

        mill.constants.DebugLog.println(s"MillDaemonRpcServer.runCommand: exitCode=$exitCode")
        data.exitCode.set(exitCode)

        DaemonRpc.RunCommandResult(exitCode)
      }
    )

    mill.constants.DebugLog.println(s"MillDaemonRpcServer.handleConnection: running RPC server")
    serverLog("handleConnection: running RPC server")

    // Run the RPC server - this blocks until the client disconnects
    rpcServer.run()

    val exitCode = data.exitCode.get()
    mill.constants.DebugLog.println(s"MillDaemonRpcServer.handleConnection: RPC server finished, exitCode=$exitCode")
    serverLog(s"handleConnection: RPC server finished, exitCode=$exitCode")

    exitCode
  }

  override def endConnection(
      connectionData: ConnectionData,
      data: Option[MillDaemonRpcServer.DaemonServerData],
      result: Option[Int]
  ): Unit = {
    mill.constants.DebugLog.println(s"MillDaemonRpcServer.endConnection: result=$result")
    serverLog(s"endConnection: result=$result")

    // Flush before closing
    System.out.flush()
    System.err.flush()

    // Close the connection
    try {
      connectionData.serverToClient.flush()
      connectionData.serverToClient.close()
    } catch {
      case _: Exception =>
      // Client may have died
    }
  }

  def systemExit(exitCode: Int): Nothing = sys.exit(exitCode)

  def exitCodeServerTerminated: Int = ClientUtil.ServerExitPleaseRetry()

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

object MillDaemonRpcServer {
  case class DaemonServerData(
      writtenExitCode: AtomicBoolean,
      exitCode: AtomicInteger
  )
}
