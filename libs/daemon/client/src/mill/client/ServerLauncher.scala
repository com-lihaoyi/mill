package mill.client

import mill.client.lock.{Lock, Locks}
import mill.constants.DaemonFiles

import java.io.{BufferedInputStream, BufferedOutputStream, OutputStream}
import java.net.{InetAddress, Socket}

/**
 * Client side code that interacts with `Server.scala` in order to launch a generic
 * long-lived background daemon.
 */
object ServerLauncher {

  case class Launched(port: Int, socket: Option[Socket], launchedServer: LaunchedServer)
      extends AutoCloseable {
    override def close(): Unit = {
      // Swallow exceptions if the close fails
      try socket.foreach(_.close())
      catch { case _: Exception => }
    }
  }

  /**
   * Run client logic with a connection established to a Mill server.
   */
  def runWithConnection[A](
      connection: Socket,
      closeConnectionAfterClientLogic: Boolean,
      sendInitData: OutputStream => Unit,
      runClientLogic: (BufferedInputStream, BufferedOutputStream) => A
  ): A = {
    val socketInputStream = new BufferedInputStream(connection.getInputStream)
    val socketOutputStream = new BufferedOutputStream(connection.getOutputStream)
    sendInitData(socketOutputStream)
    socketOutputStream.flush()
    val result = runClientLogic(socketInputStream, socketOutputStream)
    if (closeConnectionAfterClientLogic) socketInputStream.close()
    result
  }

  /**
   * Establishes a connection to the Mill server by acquiring necessary locks and potentially
   * starting a new server process if one is not already running.
   */
  def launchOrConnectToServer(
      locks: Locks,
      daemonDir: os.Path,
      serverInitWaitMillis: Int,
      initServer: () => LaunchedServer,
      onFailure: ServerLaunchResult.ServerDied => Unit,
      log: String => Unit,
      openSocket: Boolean
  ): Launched = {
    log(s"Acquiring the launcher lock: ${locks.launcherLock}")
    val locked = locks.launcherLock.lock()
    try {
      retryWithTimeout(serverInitWaitMillis, "server launch failed") { () =>
        try {
          log("launchOrConnectToServer attempt")

          ensureServerIsRunning(locks, daemonDir, initServer, serverInitWaitMillis / 3, log) match {
            case ServerLaunchResult.Success(server) =>
              Some(connectToServer(daemonDir, server, openSocket, log))

            case ServerLaunchResult.AlreadyRunning(server) =>
              Some(connectToServer(daemonDir, server, openSocket, log))

            case processDied: ServerLaunchResult.ServerDied =>
              onFailure(processDied)
              throw new IllegalStateException(processDied.toString)
          }
        } catch {
          case e: Exception => throw new RuntimeException(e)
        }
      }
    } finally {
      locked.release()
    }
  }

  private def connectToServer(
      daemonDir: os.Path,
      server: LaunchedServer,
      openSocket: Boolean,
      log: String => Unit
  ): Launched = {
    log(s"Reading server port: $daemonDir")
    val port = os.read(daemonDir / DaemonFiles.socketPort).toInt

    log("Read server port")
    val socket = Option.when(openSocket) {
      log(s"Connecting: $port")
      new Socket(InetAddress.getLoopbackAddress, port)
    }

    Launched(port, socket, server)
  }

  def retryWithTimeout[A](
      timeoutMillis: Long,
      errorMessage: String
  )(supplier: () => Option[A]): A = {
    val startTimeMonotonicNanos = System.nanoTime()
    var current: Option[A] = None
    var throwable: Throwable = null
    val timeoutNanos = timeoutMillis * 1000 * 1000

    while (current.isEmpty && System.nanoTime() - startTimeMonotonicNanos < timeoutNanos) {
      try current = supplier()
      catch {
        case e: Throwable =>
          throwable = e
          Thread.sleep(1)
      }
    }

    current.getOrElse(throw new Exception(
      s"$errorMessage (timeout was ${timeoutMillis}ms)",
      throwable
    ))
  }

  /**
   * Attempts to start a server process using initServer if needed.
   */
  def ensureServerIsRunning(
      locks: Locks,
      daemonDir: os.Path,
      initServer: () => LaunchedServer,
      timeoutMillis: Long,
      log: String => Unit
  ): ServerLaunchResult = {
    os.makeDir.all(daemonDir)

    log(s"Checking if the daemon lock is available: ${locks.daemonLock}")
    retryWithTimeout(timeoutMillis, "Failed to determine server status") { () =>
      try {
        if (locks.daemonLock.probe()) {
          log("The daemon lock is available, starting the server.")
          try {
            val launchedServer = initServer()

            log(s"The server has started: $launchedServer")

            log(s"Waiting for the server to take the daemon lock: ${locks.daemonLock}")
            waitUntilDaemonTakesTheLock(locks.daemonLock, daemonDir, launchedServer) match {
              case Some(outputs) =>
                log(s"The server $launchedServer failed to start: $outputs")
                Some(ServerLaunchResult.ServerDied(launchedServer, outputs))
              case None =>
                log(s"The server $launchedServer has taken the daemon lock: ${locks.daemonLock}")
                Some(ServerLaunchResult.Success(launchedServer))
            }
          } catch {
            case e: Exception => throw new RuntimeException(e)
          }
        } else {
          log("The daemon lock is not available, there is already a server running.")
          val pidFile = daemonDir / DaemonFiles.processId
          log(s"Trying to read the process ID of a running daemon from $pidFile")
          try {
            val contents = os.read(pidFile)
            val pid = contents.toLong
            log(s"Read PID: $pid")

            val launchedServer =
              if (pid >= 0) {
                LaunchedServer.OsProcess(
                  ProcessHandle.of(pid).orElseThrow(() =>
                    new IllegalStateException(s"No process found for PID $pid")
                  )
                )
              } else LaunchedServer.TestStub // PID < 0 is only used in tests

            Some(ServerLaunchResult.AlreadyRunning(launchedServer))
          } catch {
            case _: java.io.IOException | _: NumberFormatException =>
              log("Read PID exception")
              None
          }
        }
      } catch {
        case _: Exception => None
      }
    }
  }

  /**
   * Busy-spins until the server process is running and has taken the daemonLock.
   */
  private def waitUntilDaemonTakesTheLock(
      daemonLock: Lock,
      daemonDir: os.Path,
      server: LaunchedServer
  ): Option[ServerLaunchOutputs] = {
    while (daemonLock.probe()) {
      if (!server.isAlive) return Some(readOutputs(daemonDir))
      Thread.sleep(1)
    }
    None
  }

  private def readOutputs(daemonDir: os.Path): ServerLaunchOutputs = {
    val stdout = daemonDir / DaemonFiles.stdout
    val stderr = daemonDir / DaemonFiles.stderr

    val stdoutStr = Option.when(os.exists(stdout) && os.size(stdout) > 0)(os.read(stdout))
    val stderrStr = Option.when(os.exists(stderr) && os.size(stderr) > 0)(os.read(stderr))

    ServerLaunchOutputs(stdoutStr, stderrStr)
  }
}
