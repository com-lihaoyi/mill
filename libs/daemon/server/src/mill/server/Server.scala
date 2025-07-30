package mill.server

import mill.client.lock.{Lock, Locks}
import mill.constants.{DaemonFiles, InputPumper, ProxyStream}
import sun.misc.{Signal, SignalHandler}

import java.io.{InputStream, PipedInputStream, PipedOutputStream, PrintStream}
import java.net.{InetAddress, Socket}
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Implementation of a server that binds to a random port, informs a client of the port, and accepts a client
 * connections.
 *
 * @param daemonDir directory used for exchanging pre-TCP data with a client
 * @param acceptTimeout how long to wait for a client to connect
 */
abstract class Server[PreHandleConnectionData](
    daemonDir: os.Path,
    acceptTimeout: FiniteDuration,
    locks: Locks,
    testLogEvenWhenServerIdWrong: Boolean = false
) {
  val processId: String = Server.computeProcessId()
  private val acceptTimeoutMillis = acceptTimeout.toMillis
  private val handlerName = getClass.getName

  def serverLog0(s: String): Unit = {
    if (os.exists(daemonDir) || testLogEvenWhenServerIdWrong) {
      os.write.append(daemonDir / DaemonFiles.serverLog, s"$s\n", createFolders = true)
    }
  }

  def serverLog(s: String): Unit = serverLog0(s"$processId $s")

  /**
   * Invoked before a thread that runs [[handleConnection]] is spawned.
   */
  protected def preHandleConnection(
      stdin: InputStream,
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
      stdin: InputStream,
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
    serverLog("running server in " + daemonDir)
    val initialSystemProperties = sys.props.toMap

    try {
      Server.tryLockBlock(locks.daemonLock) { locked =>
        serverLog("server file locked")
        val serverSocket = new java.net.ServerSocket(0, 0, InetAddress.getByName(null))
        Server.watchProcessIdFile(
          daemonDir / DaemonFiles.processId,
          processId,
          running = () => !serverSocket.isClosed,
          exit = msg => {
            serverLog(msg)
            serverSocket.close()
          }
        )

        // Wrapper object to encapsulate `activeConnections` and `inactiveTimestampOpt`,
        // ensuring they get incremented and decremented together across multiple threads
        // and never get out of sync
        object ConnectionTracker {
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
            inactiveTimestampOpt.foreach { inactiveTimestamp =>
              if (System.currentTimeMillis() - inactiveTimestamp > acceptTimeoutMillis) {
                serverLog(s"shutting down due inactivity")
                serverSocket.close()
              }
            }
          }
        }

        try {
          os.write.over(daemonDir / DaemonFiles.socketPort, serverSocket.getLocalPort.toString)
          serverLog("listening on port " + serverSocket.getLocalPort)

          def systemExit(exitCode: Int) = {
            // Explicitly close serverSocket before exiting otherwise it can keep the
            // server alive 500-1000ms before letting it exit properly
            serverSocket.close()
            // Explicitly release process lock to indicate this server will not be
            // taking any more requests, and a new server should be spawned if necessary.
            // Otherwise, launchers may continue trying to connect to the server and
            // failing since the socket is closed.
            locked.release()
            sys.exit(exitCode)
          }

          val timeoutThread = new Thread(
            () => {
              while (!serverSocket.isClosed) {
                Thread.sleep(1)
                ConnectionTracker.closeIfTimedOut()
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
                serverLog("handling run")
                new Thread(
                  () =>
                    try {
                      ConnectionTracker.increment()
                      runForSocket(
                        systemExit,
                        sock,
                        initialSystemProperties,
                        () => serverSocket.close()
                      )
                    } catch {
                      case e: Throwable =>
                        serverLog(e.toString + "\n" + e.getStackTrace.mkString("\n"))
                    } finally {
                      ConnectionTracker.decrement()
                      sock.close()
                    },
                  "HandleRunThread"
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
      initialSystemProperties: Map[String, String],
      serverSocketClose: () => Unit
  ): Unit = {

    /** stdout and stderr combined into one stream */
    val currentOutErr = clientSocket.getOutputStream
    val writtenExitCode = AtomicBoolean()

    def writeExitCode(code: Int): Unit = {
      if (!writtenExitCode.getAndSet(true)) {
        ProxyStream.sendEnd(currentOutErr, code)
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
        case NonFatal(_) => false
      }
    }

    def stopServer(from: String, exitCode: Int) = {
      serverLog(s"$from invoked `stopServer`, exitCode $exitCode")
      writeExitCode(exitCode)
      systemExit0(exitCode)
    }

    try {
      val socketIn = clientSocket.getInputStream
      val stdout = new PrintStream(new ProxyStream.Output(currentOutErr, ProxyStream.OUT), true)
      val stderr = new PrintStream(new ProxyStream.Output(currentOutErr, ProxyStream.ERR), true)

      val data = preHandleConnection(
        stdin = socketIn,
        stdout = stdout,
        stderr = stderr,
        stopServer = stopServer("pre-connection handler", _),
        initialSystemProperties = initialSystemProperties
      )

      @volatile var done = false
      @volatile var idle = false
      val t = new Thread(
        () =>
          try {
            // Proxy the input stream through a pair of Piped**putStream via a pumper,
            // as the `UnixDomainSocketInputStream` we get directly from the socket does
            // not properly implement `available(): Int` and thus messes up polling logic
            // that relies on that method
            //
            // TODO: this seems to be a leftover from the times when unix sockets were used, we should try removing it
            // in a separate PR.
            val proxiedSocketInput = Server.proxyInputStreamThroughPumper(socketIn)

            val exitCode = handleConnection(
              stdin = proxiedSocketInput,
              stdout = stdout,
              stderr = stderr,
              stopServer = stopServer("connection handler", _),
              setIdle = idle = _,
              initialSystemProperties = initialSystemProperties,
              data = data
            )

            serverLog(s"connection handler finished, exitCode $exitCode")
            writeExitCode(exitCode)
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
        case NonFatal(_) => /*do nothing*/
      }
    }
  }
}
object Server {

  /** Immediately stops the server reporting the provided exit code to all clients. */
  @FunctionalInterface trait StopServer {
    def apply(exitCode: Int): Nothing
  }

  /** Controls whether the server is considered idle. */
  @FunctionalInterface trait SetIdle {

    /** @param idle true when server is not processing any requests, false when server is processing a request. */
    def apply(idle: Boolean): Unit
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

  def computeProcessId(): String = "pid" + ProcessHandle.current().pid()

  def checkProcessIdFile(processIdFile: os.Path, processId: String): Option[String] = {
    Try(os.read(processIdFile)) match {
      case scala.util.Failure(_) => Some(s"processId file missing")

      case scala.util.Success(s) =>
        Option.when(s != processId) {
          s"processId file contents $s does not match processId $processId"
        }
    }

  }

  def watchProcessIdFile(
      processIdFile: os.Path,
      processId: String,
      running: () => Boolean,
      exit: String => Unit
  ): Unit = {
    os.write.over(processIdFile, processId, createFolders = true)

    val processIdThread = new Thread(
      () =>
        while (running()) {
          checkProcessIdFile(processIdFile, processId) match {
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

  private def proxyInputStreamThroughPumper(in: InputStream): PipedInputStream = {
    val pipedInput = new PipedInputStream()
    val pipedOutput = new PipedOutputStream(pipedInput)
    val pumper = new InputPumper(() => in, () => pipedOutput, /* checkAvailable */ false)
    val pumperThread = new Thread(pumper, "proxyInputStreamThroughPumper")
    pumperThread.setDaemon(true)
    pumperThread.start()
    pipedInput
  }
}
