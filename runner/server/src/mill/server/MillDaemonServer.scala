package mill.server

import mill.api.daemon.SystemStreams
import mill.client.*
import mill.client.lock.{Lock, Locks}
import mill.constants.{ProxyStream}
import mill.constants.OutFiles.OutFiles
import mill.server.MillDaemonServer.DaemonServerData
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
    outDir: os.Path,
    testLogEvenWhenServerIdWrong: Boolean = false
) extends Server[DaemonServerData, Int](Server.Args(
      daemonDir = daemonDir,
      acceptTimeout = Some(acceptTimeout),
      locks = locks,
      testLogEvenWhenServerIdWrong = testLogEvenWhenServerIdWrong,
      bufferSize = 4 * 1024
    )) {

  def outLock: mill.client.lock.Lock

  private var stateCache: State = initialStateCache

  def initialStateCache: State

  private var lastMillVersion = Option.empty[String]
  private var lastJavaVersion = Option.empty[String]

  override def connectionHandlerThreadName(socket: Socket): String =
    s"MillServerActionRunner(${socket.getInetAddress}:${socket.getPort})"

  override def checkIfClientAlive(
      connectionData: ConnectionData,
      data: DaemonServerData
  ): Boolean = {
    ProxyStream.sendHeartbeat(connectionData.serverToClient)
    true
  }

  /**
   * Invoked before a thread that runs [[handleConnection]] is spawned.
   */
  override def prepareConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer
  ): DaemonServerData = {
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

    serverLog(s"preHandleConnection ${connectionData.socketName}")
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
        out = outDir,
        millActiveCommandMessage = "checking server mill version and java version",
        streams = new mill.api.daemon.SystemStreams(
          new PrintStream(mill.api.daemon.DummyOutputStream),
          new PrintStream(mill.api.daemon.DummyOutputStream),
          mill.api.daemon.DummyInputStream
        ),
        outLock = outLock,
        setIdle = _ => ()
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
          ClientUtil.ServerExitPleaseRetry()
        )
      }
    }
    lastMillVersion = Some(clientMillVersion)
    lastJavaVersion = Some(clientJavaVersion)

    DaemonServerData(stdout, stderr, AtomicBoolean(false), initData)
  }

  override def handleConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer,
      setIdle: Server.SetIdle,
      data: DaemonServerData
  ): Int = {
    val (result, newStateCache) = main0(
      data.clientData.args,
      stateCache,
      data.clientData.interactive,
      new SystemStreams(data.stdout, data.stderr, connectionData.clientToServer),
      data.clientData.env.asScala.toMap,
      setIdle(_),
      data.clientData.userSpecifiedProperties.asScala.toMap,
      connectionData.initialSystemProperties,
      stopServer = stopServer
    )

    stateCache = newStateCache
    val exitCode = if (result) 0 else 1

    serverLog(s"connection handler finished, sending exitCode $exitCode to client")
    exitCode
  }

  override def endConnection(
      connectionData: ConnectionData,
      data: Option[DaemonServerData],
      result: Option[Int]
  ): Unit = {
    // flush before closing the socket
    System.out.flush()
    System.err.flush()

    if (!data.exists(_.writtenExitCode.getAndSet(true) == true)) {
      try {
        ProxyStream.sendEnd(connectionData.serverToClient, result.getOrElse(1))
        connectionData.serverToClient.flush()
        connectionData.serverToClient.close()
      } catch {
        case _: Exception =>
        // Sometimes the client may have died or gone away on its own, in that case
        // just catch and swallow the exception so we don't blow up the server thread.
      }
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
      stopServer: Server.StopServer
  ): (Boolean, State)

}

object MillDaemonServer {
  case class DaemonServerData(
      stdout: PrintStream,
      stderr: PrintStream,
      writtenExitCode: AtomicBoolean,
      clientData: ClientInitData
  )
  def withOutLock[T](
      noBuildLock: Boolean,
      noWaitForBuildLock: Boolean,
      out: os.Path,
      millActiveCommandMessage: String,
      streams: SystemStreams,
      outLock: Lock,
      setIdle: Boolean => Unit
  )(t: => T): T = {
    if (noBuildLock) t
    else {
      def activeTaskString =
        try os.read(out / OutFiles.millActiveCommand)
        catch {
          case NonFatal(_) => "<unknown>"
        }

      def activeTaskPrefix = s"Another Mill process is running '$activeTaskString',"

      setIdle(true)
      Using.resource {
        val tryLocked = outLock.tryLock()
        if (tryLocked.isLocked) tryLocked
        else if (noWaitForBuildLock) throw new Exception(s"$activeTaskPrefix failing")
        else {
          streams.err.println(s"$activeTaskPrefix waiting for it to be done...")
          outLock.lock()
        }
      } { _ =>
        setIdle(false)
        if (Thread.interrupted()) throw new InterruptedException()
        os.write.over(out / OutFiles.millActiveCommand, millActiveCommandMessage)
        try t
        finally os.remove.all(out / OutFiles.millActiveCommand)
      }
    }
  }
}
