package mill.client

import mill.client.lock.{Lock, Locks}
import mill.constants.DaemonFiles

import java.io.{BufferedInputStream, BufferedOutputStream, OutputStream}
import java.net.{InetAddress, Socket}
import java.nio.file.{Files, Path}
import scala.compiletime.uninitialized

/**
 * Client side code that interacts with `Server.scala` in order to launch a generic
 * long-lived background daemon.
 */
object ServerLauncher {

  class Launched extends AutoCloseable {
    var port: Int = 0
    var socket: Socket = uninitialized
    var launchedServer: LaunchedServer = uninitialized

    override def close(): Unit = {
      // Swallow exceptions if the close fails
      try socket.close()
      catch { case _: Exception => }
    }
  }

  trait InitServer {
    def init(): LaunchedServer
  }

  trait RunClientLogicWithStreams[A] {
    def run(inStream: BufferedInputStream, outStream: BufferedOutputStream): A
  }

  /**
   * Run client logic with a connection established to a Mill server.
   */
  def runWithConnection[A](
      connection: Socket,
      closeConnectionAfterClientLogic: Boolean,
      sendInitData: OutputStream => Unit,
      runClientLogic: RunClientLogicWithStreams[A]
  ): A = {
    val socketInputStream = new BufferedInputStream(connection.getInputStream)
    val socketOutputStream = new BufferedOutputStream(connection.getOutputStream)
    sendInitData(socketOutputStream)
    socketOutputStream.flush()
    val result = runClientLogic.run(socketInputStream, socketOutputStream)
    if (closeConnectionAfterClientLogic) socketInputStream.close()
    result
  }

  /**
   * Establishes a connection to the Mill server by acquiring necessary locks and potentially
   * starting a new server process if one is not already running.
   */
  def launchOrConnectToServer(
      locks: Locks,
      daemonDir: Path,
      serverInitWaitMillis: Int,
      initServer: InitServer,
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

          val result = ensureServerIsRunning(locks, daemonDir, initServer, serverInitWaitMillis / 3, log)
          result match {
            case ServerLaunchResult.Success(server) =>
              log(s"Reading server port: ${daemonDir.toAbsolutePath}")
              val port = Files.readString(daemonDir.resolve(DaemonFiles.socketPort)).toInt

              val launched = new Launched()
              launched.port = port
              log("Read server port")
              if (openSocket) {
                log(s"Connecting: $port")
                val connected = new Socket(InetAddress.getLoopbackAddress, port)
                launched.socket = connected
              }
              launched.launchedServer = server
              Some(launched)

            case ServerLaunchResult.AlreadyRunning(server) =>
              log(s"Reading server port: ${daemonDir.toAbsolutePath}")
              val port = Files.readString(daemonDir.resolve(DaemonFiles.socketPort)).toInt

              val launched = new Launched()
              launched.port = port
              log("Read server port")
              if (openSocket) {
                log(s"Connecting: $port")
                val connected = new Socket(InetAddress.getLoopbackAddress, port)
                launched.socket = connected
              }
              launched.launchedServer = server
              Some(launched)

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

  def retryWithTimeout[A](timeoutMillis: Long, errorMessage: String)(supplier: () => Option[A]): A = {
    val startTimeMonotonicNanos = System.nanoTime()
    var current: Option[A] = None
    var throwable: Throwable = null
    val timeoutNanos = timeoutMillis * 1000 * 1000

    while (current.isEmpty && System.nanoTime() - startTimeMonotonicNanos < timeoutNanos) {
      try {
        current = supplier()
      } catch {
        case e: Throwable =>
          throwable = e
          Thread.sleep(1)
      }
    }

    current match {
      case Some(value) => value
      case None => throw new Exception(s"$errorMessage (timeout was ${timeoutMillis}ms)", throwable)
    }
  }

  /**
   * Attempts to start a server process using InitServer if needed.
   */
  def ensureServerIsRunning(
      locks: Locks,
      daemonDir: Path,
      initServer: InitServer,
      timeoutMillis: Long,
      log: String => Unit
  ): ServerLaunchResult = {
    Files.createDirectories(daemonDir)

    log(s"Checking if the daemon lock is available: ${locks.daemonLock}")
    retryWithTimeout(timeoutMillis, "Failed to determine server status") { () =>
      try {
        if (locks.daemonLock.probe()) {
          log("The daemon lock is available, starting the server.")
          try {
            val launchedServer = initServer.init()

            log(s"The server has started: $launchedServer")

            log(s"Waiting for the server to take the daemon lock: ${locks.daemonLock}")
            val maybeLaunchFailed = waitUntilDaemonTakesTheLock(locks.daemonLock, daemonDir, launchedServer)
            maybeLaunchFailed match {
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
          val pidFile = daemonDir.resolve(DaemonFiles.processId)
          log(s"Trying to read the process ID of a running daemon from ${pidFile.toAbsolutePath}")
          try {
            val contents = Files.readString(pidFile)
            val pid = contents.toLong
            log(s"Read PID: $pid")

            val launchedServer =
              if (pid >= 0) {
                LaunchedServer.OsProcess(
                  ProcessHandle.of(pid).orElseThrow(() =>
                    new IllegalStateException(s"No process found for PID $pid")
                  )
                )
              } else {
                // PID < 0 is only used in tests
                LaunchedServer.TestStub
              }
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
      daemonDir: Path,
      server: LaunchedServer
  ): Option[ServerLaunchOutputs] = {
    while (daemonLock.probe()) {
      val maybeLaunchFailed = checkIfLaunchFailed(daemonDir, server)
      if (maybeLaunchFailed.isDefined) return maybeLaunchFailed
      Thread.sleep(1)
    }
    None
  }

  private def checkIfLaunchFailed(daemonDir: Path, server: LaunchedServer): Option[ServerLaunchOutputs] = {
    if (server.isAlive) None
    else Some(readOutputs(daemonDir))
  }

  private def readOutputs(daemonDir: Path): ServerLaunchOutputs = {
    val stdout = daemonDir.toAbsolutePath.resolve(DaemonFiles.stdout)
    val stderr = daemonDir.toAbsolutePath.resolve(DaemonFiles.stderr)

    val stdoutStr =
      if (Files.exists(stdout) && Files.size(stdout) > 0) Some(Files.readString(stdout))
      else None

    val stderrStr =
      if (Files.exists(stderr) && Files.size(stderr) > 0) Some(Files.readString(stderr))
      else None

    ServerLaunchOutputs(stdoutStr, stderrStr)
  }
}
