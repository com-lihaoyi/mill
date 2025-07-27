package mill.server

import mill.api.daemon.SystemStreams
import mill.constants.ProxyStream.Output
import mill.client.lock.{Lock, Locks}
import mill.client.*
import mill.constants.{DaemonFiles, InputPumper}
import mill.constants.OutFiles
import mill.constants.ProxyStream

import java.io.*
import java.net.{InetAddress, Socket}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.Using
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

/**
 * Models a long-lived server that receives requests from a client and calls a [[main0]]
 * method to run the commands in-process. Provides the command args, env variables,
 * JVM properties, wrapped input/output streams, and other metadata related to the
 * client command
 */
abstract class Server[State](
    daemonDir: os.Path,
    acceptTimeoutMillis: Int,
    locks: Locks,
    testLogEvenWhenServerIdWrong: Boolean = false
) {

  def outLock: mill.client.lock.Lock
  def out: os.Path

  private var stateCache: State = stateCache0

  /** Initial state. */
  def stateCache0: State

  var lastMillVersion = Option.empty[String]
  var lastJavaVersion = Option.empty[String]
  val processId: String = Server.computeProcessId()

  def serverLog0(s: String): Unit = {
    if (os.exists(daemonDir) || testLogEvenWhenServerIdWrong) {
      os.write.append(daemonDir / DaemonFiles.serverLog, s"$s\n", createFolders = true)
    }
  }

  def serverLog(s: String): Unit = serverLog0(s"$processId $s")

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
          def wrap(t: => Unit): Unit = synchronized { if (!serverSocket.isClosed) { t } }

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
            // Explicitly release process lock to indicate this serverwill not be
            // taking any more requests, and a new server should be spawned if necessary.
            // Otherwise launchers may continue trying to connect to the server and
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
              catch { case _: java.net.SocketException => None }

            socketOpt match {
              case Some(sock) =>
                serverLog("handling run")
                new Thread(
                  () =>
                    try {
                      ConnectionTracker.increment()
                      handleRun(
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

  def proxyInputStreamThroughPumper(in: InputStream): PipedInputStream = {
    val pipedInput = new PipedInputStream()
    val pipedOutput = new PipedOutputStream()
    pipedOutput.connect(pipedInput)
    val pumper = new InputPumper(() => in, () => pipedOutput, false)
    val pumperThread = new Thread(pumper, "proxyInputStreamThroughPumper")
    pumperThread.setDaemon(true)
    pumperThread.start()
    pipedInput
  }

  /**
   * @param systemExit stops the current process exiting with the provided exit code
   * @param initialSystemProperties [[scala.sys.SystemProperties]] that have been obtained at the start of the server process
   * @param serverSocketClose closes the server socket
   */
  def handleRun(
      systemExit: Int => Nothing,
      clientSocket: Socket,
      initialSystemProperties: Map[String, String],
      serverSocketClose: () => Unit
  ): Unit = {
    val currentOutErr = clientSocket.getOutputStream
    val writtenExitCode = AtomicBoolean()

    def writeExitCode(code: Int) = {
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
        // TODO review: _ changed to NonFatal
        case NonFatal(_) => false
      }
    }

    try {
      val stdout = new PrintStream(new Output(currentOutErr, ProxyStream.OUT), true)
      val stderr = new PrintStream(new Output(currentOutErr, ProxyStream.ERR), true)

      val socketIn = clientSocket.getInputStream
      val initData = Server.ConnectionInitData.read(socketIn)
      import initData.*

      serverLog("args " + upickle.default.write(args))
      serverLog("env " + upickle.default.write(env))
      serverLog("props " + upickle.default.write(userSpecifiedProperties))
      // Proxy the input stream through a pair of Piped**putStream via a pumper,
      // as the `UnixDomainSocketInputStream` we get directly from the socket does
      // not properly implement `available(): Int` and thus messes up polling logic
      // that relies on that method
      val proxiedSocketInput = proxyInputStreamThroughPumper(socketIn)

      val millVersionChanged = lastMillVersion.exists(_ != clientMillVersion)
      val javaVersionChanged = lastJavaVersion.exists(_ != clientJavaVersion)

      if (millVersionChanged || javaVersionChanged) {
        Server.withOutLock(
          noBuildLock = false,
          noWaitForBuildLock = false,
          out = out,
          millActiveCommandMessage = "checking server mill version and java version",
          streams = new mill.api.daemon.SystemStreams(
            new PrintStream(mill.api.daemon.DummyOutputStream),
            new PrintStream(mill.api.daemon.DummyOutputStream),
            mill.api.daemon.DummyInputStream
          ),
          outLock = outLock
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

          writeExitCode(ClientUtil.ExitServerCodeWhenVersionMismatch())
          systemExit(ClientUtil.ExitServerCodeWhenVersionMismatch())
        }
      }
      lastMillVersion = Some(clientMillVersion)
      lastJavaVersion = Some(clientJavaVersion)
      @volatile var done = false
      @volatile var idle = false
      val t = new Thread(
        () =>
          try {
            val (result, newStateCache) = main0(
              args,
              stateCache,
              interactive,
              new SystemStreams(stdout, stderr, proxiedSocketInput),
              env.toMap,
              idle = _,
              userSpecifiedProperties.toMap,
              initialSystemProperties,
              systemExit = exitCode => {
                writeExitCode(exitCode)
                systemExit(exitCode)
              }
            )

            stateCache = newStateCache
            val exitCode = if (result) 0 else 1
            serverLog("exitCode " + exitCode)
            writeExitCode(exitCode)
          } finally {
            done = true
            idle = true
          },
        "MillServerActionRunner"
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
        // TODO review: _ changed to NonFatal
        case NonFatal(_) => /*donothing*/
      }
    }
  }

  def main0(
      args: Array[String],
      stateCache: State,
      mainInteractive: Boolean,
      streams: SystemStreams,
      env: Map[String, String],
      setIdle: Boolean => Unit,
      userSpecifiedProperties: Map[String, String],
      initialSystemProperties: Map[String, String],
      systemExit: Int => Nothing
  ): (Boolean, State)

}

object Server {
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

  def withOutLock[T](
      noBuildLock: Boolean,
      noWaitForBuildLock: Boolean,
      out: os.Path,
      millActiveCommandMessage: String,
      streams: SystemStreams,
      outLock: Lock
  )(t: => T): T = {
    if (noBuildLock) t
    else {
      def activeTaskString =
        try os.read(out / OutFiles.millActiveCommand)
        catch {
          // TODO review: _ changed to NonFatal
          case NonFatal(_) => "<unknown>"
        }

      def activeTaskPrefix = s"Another Mill process is running '$activeTaskString',"

      Using.resource {
        val tryLocked = outLock.tryLock()
        if (tryLocked.isLocked) tryLocked
        else if (noWaitForBuildLock) throw new Exception(s"$activeTaskPrefix failing")
        else {
          streams.err.println(s"$activeTaskPrefix waiting for it to be done...")
          outLock.lock()
        }
      } { _ =>
        os.write.over(out / OutFiles.millActiveCommand, millActiveCommandMessage)
        try t
        finally os.remove.all(out / OutFiles.millActiveCommand)
      }
    }
  }
}
