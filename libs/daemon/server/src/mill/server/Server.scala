package mill.server

import mill.client.lock.{Lock, Locks}
import mill.constants.{DaemonFiles, ProxyStream}
import sun.misc.{Signal, SignalHandler}

import java.io.{BufferedInputStream, PrintStream}
import java.net.{InetAddress, Socket, SocketAddress}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Implementation of a server that binds to a random port, informs a client of the port, and accepts a client
 * connections.
 *
 * @param daemonDir directory used for exchanging pre-TCP data with a client
 * @param acceptTimeout shuts down after this timeout if no clients are connected
 */
abstract class Server(
    daemonDir: os.Path,
    acceptTimeout: Option[FiniteDuration],
    locks: Locks,
    testLogEvenWhenServerIdWrong: Boolean = false
) {
  val processId: Long = Server.computeProcessId()
  private val acceptTimeoutMillis = acceptTimeout.map(_.toMillis)
  private val handlerName = getClass.getName

  private def timestampStr(): String =
    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

  def serverLog0(s: String): Unit = {
    if (os.exists(daemonDir) || testLogEvenWhenServerIdWrong) {
      os.write.append(daemonDir / DaemonFiles.serverLog, s"$s\n", createFolders = true)
    }
  }

  def serverLog(s: String): Unit = serverLog0(s"pid:$processId ${timestampStr()} $s")

  protected type PreHandleConnectionData

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
  ): PreHandleConnectionData

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
      data: PreHandleConnectionData
  ): Int

  protected def connectionHandlerThreadName(socket: Socket): String =
    s"ConnectionHandler($handlerName, ${socket.getInetAddress}:${socket.getPort})"

  def run(): Unit = {
    serverLog(s"running server in $daemonDir")
    serverLog(s"acceptTimeout=$acceptTimeout")
    val initialSystemProperties = sys.props.toMap
    val socketPortFile = daemonDir / DaemonFiles.socketPort

    try {
      Server.tryLockBlock(locks.daemonLock) { locked =>
        serverLog("server file locked")
        val serverSocket = new java.net.ServerSocket(0, 0, InetAddress.getByName(null))
        Server.watchProcessIdFile(
          daemonDir / DaemonFiles.processId,
          processId,
          running = () => !serverSocket.isClosed,
          exit = msg => {
            serverLog(s"watchProcessIdFile: $msg")
            serverSocket.close()
          },
          log = serverLog
        )

        // Wrapper object to encapsulate `activeConnections` and `inactiveTimestampOpt`,
        // ensuring they get incremented and decremented together across multiple threads
        // and never get out of sync
        object connectionTracker {
          private var activeConnections = 0
          private var inactiveTimestampOpt: Option[Long] = None

          def wrap(t: => Unit): Unit = synchronized {
            if (!serverSocket.isClosed) {
              t
            }
          }

          def increment(): Unit = wrap {
            activeConnections += 1
            serverLog(s"$activeConnections active connections")
            inactiveTimestampOpt = None
          }

          def decrement(): Unit = wrap {
            activeConnections -= 1
            serverLog(s"$activeConnections active connections")
            if (activeConnections == 0) {
              inactiveTimestampOpt = Some(System.currentTimeMillis())
            }
          }

          def closeIfTimedOut(): Unit = wrap {
            // Explicit matching as we're doing this every 1ms.
            acceptTimeoutMillis match {
              case None => // Do nothing
              case Some(acceptTimeoutMillis) =>
                inactiveTimestampOpt match {
                  case None => // Do nothing
                  case Some(inactiveTimestamp) =>
                    if (System.currentTimeMillis() - inactiveTimestamp > acceptTimeoutMillis) {
                      serverLog(s"shutting down due inactivity")
                      serverSocket.close()
                    }
                }
            }
          }
        }

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
            locked.release()
            serverLog("daemonLock released")

            sys.exit(exitCode)
          }

          val timeoutThread = new Thread(
            () => {
              while (!serverSocket.isClosed) {
                Thread.sleep(1)
                connectionTracker.closeIfTimedOut()
              }
            },
            "MillServerTimeoutThread"
          )
          timeoutThread.start()

          while (!serverSocket.isClosed) {
            val socketOpt =
              try Some(serverSocket.accept())
              catch {
                case _: java.net.SocketException => None
              }

            socketOpt match {
              case Some(sock) =>
                val socketInfo = Server.SocketInfo(sock)
                serverLog(s"handling run for $socketInfo")
                new Thread(
                  () =>
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
                    },
                  s"HandleRunThread-$socketInfo"
                ).start()
              case None =>
            }
          }

        } finally serverSocket.close()
      }.getOrElse(throw new Exception("Mill server process already present"))
    } catch {
      case e: Throwable =>
        serverLog("server loop error: " + e)
        serverLog("server loop stack trace: " + e.getStackTrace.mkString("\n"))
        throw e
    } finally {
      // Make sure no one would read the old socket port file
      serverLog(s"removing $socketPortFile")
      os.remove(socketPortFile)

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

    /** stdout and stderr combined into one stream */
    val currentOutErr = clientSocket.getOutputStream
    val writtenExitCode = AtomicBoolean()

    def writeExitCode(exitCode: Int): Unit = {
      if (!writtenExitCode.getAndSet(true)) {
        ProxyStream.sendEnd(currentOutErr, exitCode)
      }
    }

    // We cannot use Socket#{isConnected, isClosed, isBound} because none of these
    // detect client-side connection closing, so instead we send a no-op heartbeat
    // message to see if the socket can receive data.
    def checkClientAlive() = {
      try {
        ProxyStream.sendHeartbeat(currentOutErr)
        true
      } catch {
        case NonFatal(err) =>
          serverLog(
            s"error sending heartbeat, client seems to be dead: $err\n\n${err.getStackTrace.mkString("\n")}"
          )
          false
      }
    }

    def stopServer(from: String, reason: String, exitCode: Int) = {
      serverLog(s"$from invoked `stopServer` (reason: $reason), exitCode $exitCode")
      writeExitCode(exitCode)
      systemExit0(reason, exitCode)
    }

    try {
      val socketIn = BufferedInputStream(clientSocket.getInputStream, ProxyStream.MAX_CHUNK_SIZE)

      val stdout =
        new PrintStream(new ProxyStream.Output(currentOutErr, ProxyStream.StreamType.OUT), true)
      val stderr =
        new PrintStream(new ProxyStream.Output(currentOutErr, ProxyStream.StreamType.ERR), true)

      val data = preHandleConnection(
        socketInfo = socketInfo,
        stdin = socketIn,
        stdout = stdout,
        stderr = stderr,
        stopServer =
          (reason, exitCode) => stopServer("pre-connection handler", reason, exitCode),
        initialSystemProperties = initialSystemProperties
      )

      @volatile var done = false
      @volatile var idle = false
      val t = new Thread(
        () =>
          try {
            val exitCode = handleConnection(
              socketInfo = socketInfo,
              stdin = socketIn,
              stdout = stdout,
              stderr = stderr,
              stopServer =
                (reason, exitCode) => stopServer("connection handler", reason, exitCode),
              setIdle = idle = _,
              initialSystemProperties = initialSystemProperties,
              data = data
            )

            serverLog(s"connection handler finished, sending exitCode $exitCode to client")
            writeExitCode(exitCode)
          } catch {
            case e: Throwable =>
              serverLog("connection handler error: " + e)
              serverLog("connection handler stack trace: " + e.getStackTrace.mkString("\n"))
              writeExitCode(1)
          } finally {
            done = true
            idle = true
          },
        connectionHandlerThreadName(clientSocket)
      )
      t.start()

      // We cannot simply use Lock#await here, because the filesystem doesn't
      // realize the launcherLock/daemonLock are held by different threads in the
      // two processes and gives a spurious deadlock error
      while (!done && checkClientAlive()) Thread.sleep(1)

      if (!idle) {
        serverLog("client interrupted while server was executing command")
        serverSocketClose()
      }

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

      // flush before closing the socket
      System.out.flush()
      System.err.flush()
    } finally {
      try writeExitCode(1) // Send a termination if it has not already happened
      catch {
        case NonFatal(err) =>
          serverLog(
            s"error sending exit code 1, client seems to be dead: $err\n\n${err.getStackTrace.mkString("\n")}"
          )
      }
    }
  }
}
object Server {

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

  /** Immediately stops the server reporting the provided exit code to all clients. */
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
      exit: String => Unit,
      log: String => Unit
  ): Unit = {
    val processIdStr = processId.toString

    log(s"watching processId file (expected content = $processIdStr): $processIdFile")
    os.write.over(processIdFile, processIdStr, createFolders = true)

    val processIdThread = new Thread(
      () =>
        while (running()) {
          checkProcessIdFile(processIdFile, processIdStr) match {
            case None => Thread.sleep(100)
            case Some(msg) => exit(msg)
          }
        },
      "Process ID Checker Thread"
    )
    processIdThread.setDaemon(true)
    processIdThread.start()
  }

  def tryLockBlock[T](lock: Lock)(block: mill.client.lock.TryLocked => T): Option[T] = {
    lock.tryLock() match {
      case null => None
      case l =>
        if (l.isLocked) {
          try Some(block(l))
          finally l.release()
        } else {
          None
        }
    }
  }
}
