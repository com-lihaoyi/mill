package mill.server

import mill.api.daemon.StartThread
import mill.client.lock.{Lock, Locks, TryLocked}
import mill.constants.{DaemonFiles, SocketUtil}
import mill.server.Server.ConnectionData
import sun.misc.{Signal, SignalHandler}

import java.io.{BufferedInputStream, BufferedOutputStream}
import java.net.{InetAddress, ServerSocket, Socket, SocketAddress, SocketException}
import java.nio.channels.ClosedByInterruptException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Implementation of a server that binds to a random port, informs a client of the port, and accepts a client
 * connections.
 */
abstract class Server[PrepareConnectionData, HandleConnectionData](args: Server.Args) {
  import args.*

  val processId: Long = Server.computeProcessId()
  private val acceptTimeoutMillis = acceptTimeout.map(_.toMillis)
  private val handlerName = getClass.getName

  private def timestampStr(): String =
    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

  def serverLog0(s: String): Unit = {
    if (os.exists(daemonDir) || testLogEvenWhenServerIdWrong) {
      try os.write.append(daemonDir / DaemonFiles.serverLog, s"$s\n", createFolders = true)
      catch {
        case _: ClosedByInterruptException => // write was interrupted with a thread interrupt
      }
    }
  }

  def serverLog(s: String): Unit =
    serverLog0(s"pid:$processId ${timestampStr()} [t${Thread.currentThread().getId}] $s")

  def prepareConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer
  ): PrepareConnectionData

  def handleConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer,
      setIdle: Server.SetIdle,
      data: PrepareConnectionData
  ): HandleConnectionData

  def endConnection(
      connectionData: ConnectionData,
      data: Option[PrepareConnectionData],
      result: Option[HandleConnectionData]
  ): Unit

  def connectionHandlerThreadName(socket: Socket): String =
    s"ConnectionHandler($handlerName, ${socket.getInetAddress}:${socket.getPort})"

  /** Returns true if the client is still alive. Invoked from another thread. */
  def checkIfClientAlive(connectionData: ConnectionData, data: PrepareConnectionData): Boolean

  def run(): Unit = {
    serverLog(s"running server in $daemonDir")
    serverLog(s"acceptTimeout=$acceptTimeout")
    val initialSystemProperties = sys.props.toMap
    val socketPortFile = daemonDir / DaemonFiles.socketPort

    def removeSocketPortFile(): Unit = {
      // Make sure no one would read the old socket port file
      serverLog(s"removing $socketPortFile")
      os.remove(socketPortFile)
      serverLog(s"removed $socketPortFile")
    }

    try {
      Server.tryLockBlock(
        locks.daemonLock,
        beforeClose = () => {
          removeSocketPortFile()
          serverLog("releasing daemonLock")
        },
        afterClose = () => {
          serverLog("daemonLock released")
        }
      ) { locked =>
        serverLog("server file locked")
        val serverSocket = new java.net.ServerSocket(0, 0, InetAddress.getByName(null))
        Server.watchProcessIdFile(
          daemonDir / DaemonFiles.processId,
          processId,
          running = () => !serverSocket.isClosed,
          exit = msg => {
            serverLog(s"watchProcessIdFile: $msg")
            serverSocket.close()
          }
        )

        val connectionTracker =
          new Server.ConnectionTracker(serverLog, acceptTimeoutMillis, serverSocket)

        try {
          os.write.over(socketPortFile, serverSocket.getLocalPort.toString)
          serverLog("listening on port " + serverSocket.getLocalPort)

          def systemExit(reason: String, exitCode: Int) = {
            serverLog(
              s"`systemExit` invoked (reason: $reason), shutting down with exit code $exitCode"
            )

            // Explicitly close serverSocket before exiting otherwise it can keep the
            // server alive 500-1000ms before letting it exit properly
            serverSocket.close()
            serverLog("serverSocket closed")

            // Explicitly release process lock to indicate this server will not be
            // taking any more requests, and a new server should be spawned if necessary.
            // Otherwise, launchers may continue trying to connect to the server and
            // failing since the socket is closed.
            locked.close()

            sys.exit(exitCode)
          }

          StartThread("MillServerTimeoutThread") {
            while (!serverSocket.isClosed) {
              Thread.sleep(1)
              connectionTracker.closeIfTimedOut()
            }
          }

          while (!serverSocket.isClosed) {
            val socketOpt =
              try Some(serverSocket.accept())
              catch {
                case _: java.net.SocketException => None
              }

            for (sock <- socketOpt) {
              val socketInfo = Server.SocketInfo(sock)
              serverLog(s"handling run for $socketInfo")
              StartThread(s"HandleRunThread-$socketInfo") {
                try {
                  connectionTracker.increment()
                  runForSocket(
                    systemExit,
                    sock,
                    socketInfo,
                    initialSystemProperties,
                    () => serverSocket.close()
                  )
                } catch {
                  case e: Throwable =>
                    serverLog(
                      s"""$socketInfo error: $e
                         |
                         |${e.getStackTrace.mkString("\n")}
                         |""".stripMargin
                    )
                } finally {
                  connectionTracker.decrement()
                  sock.close()
                }
              }
            }
          }

        } finally serverSocket.close()
      }
    } catch {
      case e: Throwable =>
        serverLog("server loop error: " + e)
        serverLog("server loop stack trace: " + e.getStackTrace.mkString("\n"))
        throw e
    } finally {
      serverLog("exiting server")
    }
  }

  /**
   * Handles the necessary plumbing for a single client connection.
   *
   * @param initialSystemProperties [[scala.sys.SystemProperties]] that have been obtained at the start of the server process
   * @param serverSocketClose       closes the server socket
   */
  private def runForSocket(
      systemExit0: Server.StopServer,
      clientSocket: Socket,
      socketInfo: Server.SocketInfo,
      initialSystemProperties: Map[String, String],
      serverSocketClose: () => Unit
  ): Unit = {
    // According to https://pzemtsov.github.io/2015/01/19/on-the-benefits-of-stream-buffering-in-Java.html it seems that
    // buffering on the application level is still beneficial due to syscall overhead, even if kernel has its own
    // socket buffers.
    val clientToServer = BufferedInputStream(clientSocket.getInputStream, bufferSize)
    val serverToClient = BufferedOutputStream(clientSocket.getOutputStream, bufferSize)

    val connectionData =
      ConnectionData(socketInfo, clientToServer, serverToClient, initialSystemProperties)

    def stopServer(
        from: String,
        reason: String,
        exitCode: Int,
        data: Option[PrepareConnectionData]
    ): Nothing = {
      serverLog(s"$from invoked `stopServer` (reason: $reason), exitCode $exitCode")
      endConnection(connectionData, data, None)
      systemExit0(reason, exitCode)
    }

    val data = prepareConnection(
      connectionData,
      stopServer =
        (reason, exitCode) => stopServer("pre-connection handler", reason, exitCode, data = None)
    )

    // We cannot use Socket#{isConnected, isClosed, isBound} because none of these
    // detect client-side connection closing, so instead we send a no-op heartbeat
    // message to see if the socket can receive data.
    @volatile var lastClientAlive = true

    def checkClientAlive() = {
      val result =
        try checkIfClientAlive(connectionData, data)
        catch {
          case e: SocketException if SocketUtil.clientHasClosedConnection(e) =>
            serverLog(s"client has closed connection")
            false
          case NonFatal(err) =>
            serverLog(
              s"error checking for client liveness, assuming client to be dead: $err\n\n${err.getStackTrace.mkString("\n")}"
            )
            false
        }
      lastClientAlive = result
      result
    }

    try {
      @volatile var done = false
      @volatile var idle = false
      

      val t = StartThread(connectionHandlerThreadName(clientSocket)) {
        var result: Option[HandleConnectionData] = None
        try {
          result = Some(handleConnection(
            connectionData,
            stopServer =
              (reason, exitCode) =>
                stopServer("connection handler", reason, exitCode, Some(data)),
            setIdle = idle = _,
            data = data
          ))
        } catch {
          case e: SocketException if SocketUtil.clientHasClosedConnection(e) => // do nothing
          case e: Throwable =>
            serverLog(
              s"""connection handler for $socketInfo error: $e
                 |connection handler stack trace: ${e.getStackTrace.mkString("\n")}
                 |""".stripMargin
            )

        } finally {
          endConnection(connectionData, Some(data), result)
          done = true
          idle = true
        }
      }

      // We cannot simply use Lock#await here, because the filesystem doesn't
      // realize the launcherLock/daemonLock are held by different threads in the
      // two processes and gives a spurious deadlock error
      while (!done && checkClientAlive()) Thread.sleep(1)

      if (!idle) {
        serverLog("client interrupted while server was executing command")
        serverSocketClose()
      }

      serverLog(s"done=$done, idle=$idle, lastClientAlive=$lastClientAlive")

      t.interrupt()

      // Try to give thread a moment to stop before we kill it for real
      //
      // We only give it 5ms because it's supposed to be idle at this point and this should
      // only interrupt the `Thread.sleep` it's sitting on.
      Thread.sleep(5)
      // noinspection ScalaDeprecation
      try t.stop()
      catch {
        case _: UnsupportedOperationException =>
        // nothing we can do about, removed in Java 20
        case e: java.lang.Error if e.getMessage.contains("Cleaner terminated abnormally") =>
        // ignore this error and do nothing; seems benign
      }
    } finally endConnection(connectionData, Some(data), None)
  }
}
object Server {

  // Wrapper object to encapsulate `activeConnections` and `inactiveTimestampOpt`,
  // ensuring they get incremented and decremented together across multiple threads
  // and never get out of sync
  case class ConnectionTracker(
      serverLog: String => Unit,
      acceptTimeoutMillisOpt: Option[Long],
      serverSocket: ServerSocket
  ) {
    private var activeConnections = 0
    private var inactiveTimestampOpt: Option[Long] = None

    def wrap(block: => Unit): Unit = synchronized {
      if (!serverSocket.isClosed) block
    }

    def increment(): Unit = wrap {
      activeConnections += 1
      serverLog(s"$activeConnections active connections")
      inactiveTimestampOpt = None
    }

    def decrement(): Unit = wrap {
      activeConnections -= 1
      serverLog(s"$activeConnections active connections")
      if (activeConnections == 0) inactiveTimestampOpt = Some(System.currentTimeMillis())
    }

    def closeIfTimedOut(): Unit = wrap {
      for {
        acceptTimeoutMillis <- acceptTimeoutMillisOpt
        inactiveTimestamp <- inactiveTimestampOpt
        if System.currentTimeMillis() - inactiveTimestamp > acceptTimeoutMillis
      } {
        serverLog(s"shutting down due inactivity")
        serverSocket.close()
      }
    }
  }

  /**
   * @param daemonDir directory used for exchanging pre-TCP data with a client
   * @param acceptTimeout shuts down after this timeout if no clients are connected
   * @param bufferSize size of the buffer used to read/write from/to the client
   */
  case class Args(
      daemonDir: os.Path,
      acceptTimeout: Option[FiniteDuration],
      locks: Locks,
      bufferSize: Int,
      testLogEvenWhenServerIdWrong: Boolean = false
  )

  /**
   * @param remote the address of the client
   * @param local the address of the server
   */
  case class SocketInfo(remote: SocketAddress, local: SocketAddress) {
    override def toString: String = s"SocketInfo(remote=$remote, local=$local)"
  }
  object SocketInfo {
    def apply(socket: Socket): SocketInfo =
      apply(socket.getRemoteSocketAddress, socket.getLocalSocketAddress)
  }

  /** Immediately stops the server with the given exit code. */
  @FunctionalInterface trait StopServer {
    def apply(reason: String, exitCode: Int): Nothing
  }

  /** Controls whether the server is considered idle. */
  @FunctionalInterface trait SetIdle {

    /** @param idle true when server is not processing any requests, false when server is processing a request. */
    def apply(idle: Boolean): Unit

    /** Runs the provided function, setting the server as non-idle while it runs. */
    inline def doWork[A](f: => A): A = {
      apply(false)
      try f
      finally apply(true)
    }
  }

  /// Override (by default: disable) SIGINT interrupt signal in the Mill server.
  ///
  /// This gets passed through from the client to server whenever the user
  /// hits `Ctrl-C`, which by default kills the server, which defeats the purpose
  /// of running a background daemon. Furthermore, the background daemon already
  /// can detect when the Mill client goes away, which is necessary to handle
  /// the case when a Mill client that did *not* spawn the server gets `CTRL-C`ed
  def overrideSigIntHandling(handler: SignalHandler = _ => ()): Unit = {
    Signal.handle(new Signal("INT"), handler)
  }

  def computeProcessId(): Long = ProcessHandle.current().pid()

  def checkProcessIdFile(processIdFile: os.Path, processId: String): Option[String] = {
    Try(os.read(processIdFile)) match {
      case scala.util.Failure(_) => Some(s"processId file missing: $processIdFile")

      case scala.util.Success(s) =>
        Option.when(s != processId) {
          s"processId file ($processIdFile) contents $s does not match processId $processId"
        }
    }

  }

  /** Runs a thread that invokes `exit` if the contents of `processIdFile` do not match `processId`. */
  def watchProcessIdFile(
      processIdFile: os.Path,
      processId: Long,
      running: () => Boolean,
      exit: String => Unit
  ): Unit = {
    val processIdStr = processId.toString

    os.write.over(processIdFile, processIdStr, createFolders = true)

    StartThread("Process ID Checker Thread", daemon = true) {
      while (running()) {
        checkProcessIdFile(processIdFile, processIdStr) match {
          case None => Thread.sleep(100)
          case Some(msg) => exit(msg)
        }
      }
    }
  }

  def tryLockBlock[T](
      lock: Lock,
      beforeClose: () => Unit,
      afterClose: () => Unit
  )(block: AutoCloseable => T): T = {
    // Retry taking the daemon lock, because there is a small chance it might collide
    // with the client taking the daemon lock when probing to check. But use `tryLock`
    // rather than `lock` because if the lock is truly taken by another mill daemon
    // process, we want to fail loudly rather than blocking and hanging forever
    val l = mill.client.ServerLauncher.retryWithTimeout(
      100,
      "Mill server process already present",
      () => {
        val l = lock.tryLock()
        if (l.isLocked) java.util.Optional.of[TryLocked](l)
        else java.util.Optional.empty[TryLocked]()
      }
    )

    val autoCloseable = new AutoCloseable {
      @volatile private var closed = false

      override def close(): Unit = {
        if (!closed) {
          closed = true
          beforeClose()
          l.release()
          afterClose()
        }
      }
    }

    try block(autoCloseable)
    finally autoCloseable.close()
  }

  case class ConnectionData(
      socketInfo: Server.SocketInfo,
      clientToServer: BufferedInputStream,
      serverToClient: BufferedOutputStream,
      initialSystemProperties: Map[String, String]
  )

}
