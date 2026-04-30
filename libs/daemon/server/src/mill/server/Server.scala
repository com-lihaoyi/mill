package mill.server

import mill.api.daemon.StartThread
import mill.api.daemon.internal.NonFatal
import mill.client.lock.{Lock, Locks}
import mill.constants.{DaemonFiles, SocketUtil}
import mill.server.Server.ConnectionData
import sun.misc.{Signal, SignalHandler}

import java.io.{BufferedInputStream, BufferedOutputStream, PrintWriter, StringWriter}
import java.net.{InetAddress, ServerSocket, Socket, SocketException}
import java.nio.channels.ClosedByInterruptException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * Implementation of a server that binds to a random port, informs a client of the port,
 * and accepts a client connections.
 */
abstract class Server[Prepared, Handled](args: Server.Args) {
  import args.*

  val processId: Long = Server.computeProcessId()
  val acceptTimeoutMillisOpt = acceptTimeout.map(_.toMillis)

  def serverLog0(s: String): Unit = {
    if (os.exists(daemonDir) || testLogEvenWhenServerIdWrong) {
      try os.write.append(daemonDir / DaemonFiles.serverLog, s"$s\n", createFolders = true)
      catch {
        case _: ClosedByInterruptException => // write was interrupted with a thread interrupt
      }
    }
  }

  def serverLog(s: String): Unit = {
    val timestampStr = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    serverLog0(s"pid:$processId $timestampStr [t${Thread.currentThread().getId}] $s")
  }

  def prepareConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer0[Handled]
  ): Prepared

  def handleConnection(
      connectionData: ConnectionData,
      stopServer: Server.StopServer0[Handled],
      setIdle: Server.SetIdle,
      data: Prepared
  ): Handled

  def endConnection(
      connectionData: ConnectionData,
      data: Option[Prepared],
      result: Option[Handled],
      externalShutdownReason: Option[String]
  ): Unit

  def systemExit(exitCode: Handled): Nothing

  /** Exit code to send to other clients when server is terminated while handling a request */
  def exitCodeServerTerminated: Handled

  def connectionHandlerThreadName(socket: Socket): String =
    s"ConnectionHandler(${getClass.getName}, ${socket.getInetAddress}:${socket.getPort})"

  def checkIfClientAlive(connectionData: ConnectionData, data: Prepared): Boolean

  /**
   * Optional human-readable description of what this connection is currently
   * doing, surfaced to other clients when this connection causes a daemon
   * shutdown (e.g. by being killed mid-execution). Default: no info.
   */
  def runningCommandFor(data: Prepared): Option[String] = None

  def run(): Option[Handled] = {
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
        afterClose = () => serverLog("daemonLock released")
      ) { _ =>
        runLocked(initialSystemProperties, socketPortFile)
      }
    } catch {
      case e: Throwable =>
        serverLog("server loop error: " + e)
        serverLog("server loop stack trace: " + e.getStackTrace.mkString("\n"))
        throw e
    } finally serverLog("exiting server")
  }

  def runLocked(
      initialSystemProperties: Map[String, String],
      socketPortFile: os.Path
  ): Option[Handled] = {
    serverLog("server file locked")
    val serverSocket = new ServerSocket(0, 0, InetAddress.getByName(null))
    val exitCodeVar = new AtomicReference[Option[Handled]](None)
    val draining = new AtomicReference[Boolean](false)
    val stateFile = daemonDir / DaemonFiles.daemonState

    def closeServer(exitCodeOpt: Option[Handled]) = {
      // Don't System.exit immediately, but instead store the exit code for later and close the
      // `serverSocket` so the methods return normally with a single exit point
      exitCodeVar.compareAndSet(None, exitCodeOpt)
      serverSocket.close()
    }

    // Forward declaration so connectionTracker's `onChange` can publish state
    // updates with the current `draining` flag and active count.
    lazy val connectionTracker: Server.ConnectionTracker = new Server.ConnectionTracker(
      serverLog,
      acceptTimeoutMillisOpt,
      serverSocket,
      onChange = () =>
        Server.writeDaemonState(
          stateFile,
          Server.DaemonState(
            pid = processId,
            activeConnections = connectionTracker.activeCount,
            acceptingConnections = !draining.get() && !serverSocket.isClosed
          )
        )
    )

    // Initial state publish so a launcher polling immediately on startup sees a real file.
    Server.writeDaemonState(
      stateFile,
      Server.DaemonState(
        pid = processId,
        activeConnections = 0,
        acceptingConnections = true
      )
    )

    // Reason captured by the watcher thread when it triggers a drain; read by
    // the main thread after the accept loop exits so we can log/forward it.
    val drainReason = new AtomicReference[String]("")

    Server.watchProcessIdFile(
      daemonDir / DaemonFiles.processId,
      processId,
      running = () => !serverSocket.isClosed,
      exit = msg => {
        // Don't perform the drain here — this runs in a daemon thread that
        // gets killed when the main thread System.exit's. Instead just flag
        // `draining` and close the listen socket; the main thread (in
        // `runLocked`'s finally) handles the actual drain wait synchronously.
        serverLog(s"watchProcessIdFile: $msg")
        if (draining.compareAndSet(false, true)) {
          drainReason.set(msg)
          serverLog("entering drain mode; refusing new connections")
          // Republish state so polling launchers see acceptingConnections=false
          // before they try to connect.
          Server.writeDaemonState(
            stateFile,
            Server.DaemonState(
              pid = processId,
              activeConnections = connectionTracker.activeCount,
              acceptingConnections = false
            )
          )
        }
        // Closing the listen socket triggers SocketException in accept() and
        // the main thread falls into the drain step in runLocked's finally.
        try serverSocket.close()
        catch { case NonFatal(_) => () }
      }
    )

    try {
      os.write.over(socketPortFile, serverSocket.getLocalPort.toString)
      serverLog("listening on port " + serverSocket.getLocalPort)

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
            case _: SocketException => None
          }

        for (sock <- socketOpt) {
          serverLog(s"handling run for ${sock.toString}")
          // Kicks off a separate thread to handle this particular client server
          // connection. Returns immediately without waiting for the thread to
          // complete, to allow other client connections to be processed in parallel
          StartThread(s"HandleRunThread-${sock.toString}") {
            try {
              runSocketHandler(
                sock,
                initialSystemProperties,
                closeServer(_),
                connectionTracker,
                draining
              )
            } catch {
              case e: Throwable =>
                serverLog(s"${sock.toString} error: $e\n${e.getStackTrace.mkString("\n")}")
            } finally {
              connectionTracker.decrement(sock)
              sock.close()
            }
          }
        }
      }
    } finally {
      // If we exited the accept loop because the watcher signaled a drain,
      // wait synchronously for in-flight connections to finish (or force-close
      // them after the deadline) before letting the main thread return — once
      // it returns, `MillDaemonMain0` calls `System.exit`, which would kill
      // running command handlers mid-execution.
      if (draining.get()) {
        val msg = Option(drainReason.get()).filter(_.nonEmpty).getOrElse("daemon-restart")
        val deadline = System.nanoTime() + Server.drainTimeoutMillis * 1_000_000L
        val drained = connectionTracker.waitUntilEmpty(deadline)
        if (!drained) {
          serverLog(
            s"drain timeout (${Server.drainTimeoutMillis}ms) exceeded with " +
              s"${connectionTracker.activeCount} connection(s) remaining; force-closing"
          )
          connectionTracker.closeAllConnections(Some(
            s"daemon was shut down because another launcher requested a daemon restart: $msg"
          ))
          // Brief grace period for the closed connections' end-of-stream
          // responses to flush before the JVM exits.
          val flushDeadline = System.nanoTime() + 2_000_000_000L
          connectionTracker.waitUntilEmpty(flushDeadline)
        } else {
          serverLog("drain complete; all connections finished")
        }
      }
      closeServer(None)
      try os.remove(stateFile, checkExists = false)
      catch { case NonFatal(_) => () }
    }

    exitCodeVar.get()
  }

  def runSocketHandler(
      clientSocket: Socket,
      initialSystemProperties: Map[String, String],
      closeServer0: Option[Handled] => Unit,
      connectionTracker: Server.ConnectionTracker,
      draining: AtomicReference[Boolean]
  ): Unit = {
    val connectionData = ConnectionData(
      clientSocket.toString,
      // According to https://pzemtsov.github.io/2015/01/19/on-the-benefits-of-stream-buffering-in-Java.html
      // it seems that buffering on the application level is still beneficial due to syscall
      // overhead, even if kernel has its own socket buffers.
      BufferedInputStream(clientSocket.getInputStream, bufferSize),
      BufferedOutputStream(clientSocket.getOutputStream, bufferSize),
      initialSystemProperties
    )

    @volatile var connExitCodeVar = Option.empty[Handled]

    // Guard to prevent endConnection from being called multiple times.
    // This is needed because endConnection can be triggered from multiple places:
    // - Normal completion in the finally block
    // - Client interruption in the if (!idle) block
    // - Server shutdown via closeServer
    // - Connection tracker closing other connections
    val endConnectionCalled = new java.util.concurrent.atomic.AtomicBoolean(false)
    def safeEndConnection(
        data: Option[Prepared],
        result: Option[Handled],
        externalShutdownReason: Option[String] = None
    ): Unit = {
      if (endConnectionCalled.compareAndSet(false, true)) {
        endConnection(connectionData, data, result, externalShutdownReason)
      }
    }

    def closeServer(reason: String, exitCode: Handled, data: Option[Prepared]) = {
      serverLog(
        s"`systemExit` invoked ($reason), ending connection and " +
          s"shutting down server with exit code $exitCode"
      )

      // Notify all other connected clients before shutting down, so they can retry
      connectionTracker.closeOtherConnections(clientSocket, Some(reason))
      safeEndConnection(data, Some(exitCode))
      closeServer0(Some(exitCode))
    }

    val data = prepareConnection(connectionData, closeServer(_, _, None))

    // Register this connection with the tracker, providing a callback to close it.
    // If the daemon has already started draining the socket may be closed before
    // we get here; in that case, surface the retry exit code immediately so the
    // launcher's retry loop reconnects to the new daemon.
    val accepted = connectionTracker.increment(
      clientSocket,
      reason => safeEndConnection(Some(data), Some(exitCodeServerTerminated), reason)
    )
    if (!accepted) {
      val reason = "daemon is shutting down for restart; retry on the new daemon"
      safeEndConnection(Some(data), Some(exitCodeServerTerminated), Some(reason))
      return
    }

    // We cannot use Socket#{isConnected, isClosed, isBound} because none of these
    // detect client-side connection closing, so instead we send a no-op heartbeat
    // message to see if the socket can receive data.
    var lastClientAlive = true

    def checkClientAlive() = {
      lastClientAlive =
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

      lastClientAlive
    }

    try {
      @volatile var done = false
      @volatile var idle = false

      StartThread(connectionHandlerThreadName(clientSocket)) {
        try {
          val connResult =
            handleConnection(connectionData, closeServer(_, _, Some(data)), idle = _, data)

          connExitCodeVar = Some(connResult)
        } catch {
          case e: SocketException if SocketUtil.clientHasClosedConnection(e) => // do nothing
          case e: Throwable =>
            val sw = new StringWriter()
            e.printStackTrace(new PrintWriter(sw))
            serverLog(s"connection handler for $clientSocket error: $sw")
        } finally {
          // Publish `idle` before `done`: the watcher thread exits its
          // liveness loop as soon as it observes `done`, and treats
          // `idle == false` as a client interruption that should kill the
          // daemon and all other active clients.
          idle = true
          done = true
        }
      }

      while (!done && checkClientAlive()) Thread.sleep(1)
      // Don't interrupt, just wait for the watchAndWait code to realize the client has
      // gone away when it regularly polls. `interrupt` has lots of weird side effects
      // runThread.interrupt()

      // Cross-launcher shutdown only fires when the *client* died mid-command.
      // Skip it during graceful drain — there the listen socket was closed by
      // the watcher thread, not by an interrupted client, and the
      // drain coordinator is responsible for closing peer connections.
      if (!idle && !draining.get()) {
        serverLog("client interrupted while server was executing command")
        val reason = runningCommandFor(data) match {
          case Some(cmd) =>
            s"daemon was shut down because launcher running '$cmd' was interrupted"
          case None => "daemon was shut down because another launcher was interrupted mid-execution"
        }
        // Close all other connected clients with exitCodeServerTerminated so they can retry
        connectionTracker.closeOtherConnections(clientSocket, Some(reason))
        safeEndConnection(Some(data), None) // Gracefully close the current client connection
        closeServer0(None) // Shut down the server
      }

      serverLog(s"done=$done, idle=$idle, lastClientAlive=$lastClientAlive")
    } finally safeEndConnection(Some(data), connExitCodeVar)
  }
}

object Server {
  // Wrapper object to encapsulate `activeConnections` and `inactiveTimestampOpt`,
  // ensuring they get incremented and decremented together across multiple threads
  // and never get out of sync.
  //
  // `increment` is gated on the listen socket still being open (we don't want
  // brand-new connections after the daemon has started shutting down), but
  // `decrement` and `closeOtherConnections` MUST keep working after shutdown
  // begins so the drain loop can observe the active count reaching zero.
  case class ConnectionTracker(
      serverLog: String => Unit,
      acceptTimeoutMillisOpt: Option[Long],
      serverSocket: ServerSocket,
      onChange: () => Unit = () => ()
  ) {
    private var inactiveTimestampOpt: Option[Long] = None
    private var connections = Map.empty[Socket, Option[String] => Unit]

    private def notifyChange(): Unit = {
      synchronized(notifyAll())
      try onChange()
      catch { case NonFatal(_) => }
    }

    def activeCount: Int = synchronized(connections.size)

    def waitUntilEmpty(deadlineNanos: Long): Boolean = synchronized {
      while (connections.nonEmpty) {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return false
        // wait takes millis; round up so we never sleep past the deadline.
        val millis = math.max(1L, remaining / 1_000_000L)
        wait(millis)
      }
      true
    }

    def closeOtherConnections(currentSocket: Socket, reason: Option[String]): Unit = {
      val others = synchronized {
        val snapshot = connections.iterator.filter(_._1 != currentSocket).toVector
        serverLog(s"closing ${snapshot.size} other connection(s)")
        snapshot
      }
      others.foreach { case (sock, closeCallback) =>
        try {
          closeCallback(reason)
          serverLog(s"closed connection ${sock.toString}")
        } catch {
          case NonFatal(e) =>
            serverLog(s"error closing connection ${sock.toString}: $e")
        }
      }
    }

    def closeAllConnections(reason: Option[String]): Unit = {
      val all = synchronized {
        val snapshot = connections.iterator.toVector
        serverLog(s"closing all ${snapshot.size} connection(s)")
        snapshot
      }
      all.foreach { case (sock, closeCallback) =>
        try {
          closeCallback(reason)
          serverLog(s"closed connection ${sock.toString}")
        } catch {
          case NonFatal(e) =>
            serverLog(s"error closing connection ${sock.toString}: $e")
        }
      }
    }

    /** Register a new connection. Rejected (returns false) once the listen socket has been closed. */
    def increment(socket: Socket, closeCallback: Option[String] => Unit): Boolean = {
      val accepted = synchronized {
        if (serverSocket.isClosed) false
        else {
          connections += (socket -> closeCallback)
          serverLog(s"${connections.size} active connections")
          inactiveTimestampOpt = None
          true
        }
      }
      if (accepted) notifyChange()
      accepted
    }

    def decrement(socket: Socket): Unit = {
      synchronized {
        connections -= socket
        serverLog(s"${connections.size} active connections")
        if (connections.isEmpty) inactiveTimestampOpt = Some(System.currentTimeMillis())
      }
      notifyChange()
    }

    def closeIfTimedOut(): Unit = synchronized {
      if (serverSocket.isClosed) return
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

  /** Atomic snapshot the daemon publishes so launchers can poll without an RPC round-trip. */
  case class DaemonState(
      pid: Long,
      activeConnections: Int,
      acceptingConnections: Boolean
  ) derives upickle.ReadWriter

  /**
   * Atomically write the daemon state JSON via tmp+rename so polling launchers
   * never read a half-written file. Skips the write if the daemon directory
   * has been deleted (e.g. by `rm -rf out/`); we never recreate it, since
   * doing so would defeat tests/users that expect daemon shutdown when the
   * `out/` folder disappears.
   */
  def writeDaemonState(stateFile: os.Path, state: DaemonState): Unit = {
    if (!os.exists(stateFile / os.up)) return
    val tmp = stateFile / os.up / s".${stateFile.last}.tmp"
    try {
      os.write.over(tmp, upickle.default.write(state, indent = 2), createFolders = false)
      os.move.over(tmp, stateFile, replaceExisting = true)
    } catch {
      case NonFatal(_) =>
        try os.remove(tmp, checkExists = false)
        catch { case NonFatal(_) => () }
    }
  }

  /** Read+parse the daemon state file; returns None if missing or unparseable. */
  def readDaemonState(stateFile: os.Path): Option[DaemonState] =
    try
      if (os.exists(stateFile)) Some(upickle.default.read[DaemonState](os.read(stateFile)))
      else None
    catch { case NonFatal(_) => None }

  /**
   * Drain timeout for graceful daemon-restart handover. The daemon waits at
   * most this long for in-flight connections to complete before forcibly
   * exiting; the launcher's poll loop uses the same value as a ceiling. Set
   * via `MILL_DAEMON_DRAIN_TIMEOUT_MS`; defaults to 60s.
   */
  val drainTimeoutMillis: Long =
    Option(System.getenv("MILL_DAEMON_DRAIN_TIMEOUT_MS"))
      .flatMap(s => Try(s.toLong).toOption)
      .filter(_ > 0)
      .getOrElse(60_000L)

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

  /** Immediately stops the server with the given exit code. */
  @FunctionalInterface trait StopServer0[Handle] {
    def apply(reason: String, exitCode: Handle): Unit
  }
  type StopServer = StopServer0[Int]

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
      "Mill server process already present"
    ) { () =>
      val l = lock.tryLock()
      if (l.isLocked) Some(l)
      else None
    }

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
      socketName: String,
      clientToServer: BufferedInputStream,
      serverToClient: BufferedOutputStream,
      initialSystemProperties: Map[String, String]
  )
}
