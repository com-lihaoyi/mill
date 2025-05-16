package mill.server

import mill.api.SystemStreams
import mill.constants.ProxyStream.Output
import mill.client.lock.{DoubleLock, Lock, Locks}
import mill.client.*
import mill.constants.{DaemonFiles, InputPumper}
import mill.constants.ProxyStream

import java.io.*
import java.net.{InetAddress, Socket, ServerSocket}
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.Using
import mill.constants.OutFiles

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Models a long-lived server that receives requests from a client and calls a [[main0]]
 * method to run the commands in-process. Provides the command args, env variables,
 * JVM properties, wrapped input/output streams, and other metadata related to the
 * client command
 */
abstract class Server[T](
    daemonDir: os.Path,
    acceptTimeoutMillis: Int,
    locks: Locks,
    testLogEvenWhenServerIdWrong: Boolean = false
) {

  def outLock: mill.client.lock.Lock
  def out: os.Path

  @volatile var running = true
  def exitServer(): Unit = running = false
  var stateCache = stateCache0
  def stateCache0: T

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
        Server.watchProcessIdFile(
          daemonDir / DaemonFiles.processId,
          processId,
          running = () => running,
          exit = msg => {
            serverLog(msg)
            exitServer()
          }
        )
        val serverSocket = new java.net.ServerSocket(0, 0, InetAddress.getByName(null))
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

          while (
            running && {
              interruptWithTimeout(() => serverSocket.close(), () => serverSocket.accept()) match {
                case None => false
                case Some(sock) =>
                  serverLog("handling run")
                  new Thread(
                    () =>
                      try handleRun(systemExit, sock, initialSystemProperties)
                      catch {
                        case e: Throwable =>
                          serverLog(e.toString + "\n" + e.getStackTrace.mkString("\n"))
                      } finally sock.close();,
                    "HandleRunThread"
                  ).start()
                  true
              }
            }
          ) ()
        } finally serverSocket.close()
        serverLog("server loop ended")
      }.getOrElse(throw new Exception("Mill server process already present"))
    } catch {
      case e: Throwable =>
        serverLog("server loop error: " + e)
        serverLog("server loop stack trace: " + e.getStackTrace.mkString("\n"))
        throw e
    } finally {
      serverLog("finally exitServer")
      exitServer()
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

  def interruptWithTimeout[T](close: () => Unit, t: () => T): Option[T] = {
    @volatile var interrupt = true
    @volatile var interrupted = false
    val thread = new Thread(
      () => {
        try Thread.sleep(acceptTimeoutMillis)
        catch {
          case t: InterruptedException => /* Do Nothing */
        }
        if (interrupt) {
          interrupted = true
          serverLog(s"Interrupting after ${acceptTimeoutMillis}ms")
          close()
        }
      },
      "MillSocketTimeoutInterruptThread"
    )

    thread.start()
    try {
      val res =
        try Some(t())
        catch { case e: Throwable => None }

      if (interrupted) None
      else res

    } finally {
      interrupt = false
      thread.interrupt()
    }
  }

  def handleRun(
      systemExit: Int => Nothing,
      clientSocket: Socket,
      initialSystemProperties: Map[String, String]
  ): Unit = {
    val currentOutErr = clientSocket.getOutputStream
    val writtenExitCode = AtomicBoolean()
    def writeExitCode(code: Int) = {
      if (!writtenExitCode.getAndSet(true)) {
        ProxyStream.sendEnd(currentOutErr, code)
      }
    }

    var clientDisappeared = false
    // We cannot use Socket#{isConnected, isClosed, isBound} because none of these
    // detect client-side connection closing, so instead we send a no-op heartbeat
    // message to see if the socket can receive data.
    def checkClientAlive() = {
      try {
        ProxyStream.sendHeartbeat(currentOutErr)
        true
      } catch {
        case e: Throwable =>
          clientDisappeared = true
          false
      }
    }
    try {
      val stdout = new PrintStream(new Output(currentOutErr, ProxyStream.OUT), true)
      val stderr = new PrintStream(new Output(currentOutErr, ProxyStream.ERR), true)

      val socketIn = clientSocket.getInputStream

      val interactive = socketIn.read() != 0
      val clientMillVersion = ClientUtil.readString(socketIn)
      val clientJavaVersion = ClientUtil.readString(socketIn)
      val args = ClientUtil.parseArgs(socketIn)
      val env = ClientUtil.parseMap(socketIn)
      serverLog("args " + upickle.default.write(args))
      serverLog("env " + upickle.default.write(env.asScala))
//      val userSpecifiedProperties = ClientUtil.parseMap(socketIn)
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
          streams = new mill.api.SystemStreams(
            new PrintStream(mill.api.DummyOutputStream),
            new PrintStream(mill.api.DummyOutputStream),
            mill.api.DummyInputStream
          ),
          outLock = outLock
        ) {
          if (millVersionChanged) {
            stderr.println(
              s"Mill version changed ($lastMillVersion -> $clientMillVersion), re-starting server"
            )
          }
          if (javaVersionChanged) {
            stderr.println(
              s"Java version changed ($lastJavaVersion -> $clientJavaVersion), re-starting server"
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
              env.asScala.toMap,
              idle = _,
              Map(),
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
        exitServer()
      }

      t.interrupt()
      // Try to give thread a moment to stop before we kill it for real
      Thread.sleep(5)
      try t.stop()
      catch {
        case e: UnsupportedOperationException =>
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
        case e: Throwable => /*donothing*/
      }
    }
  }

  def main0(
      args: Array[String],
      stateCache: T,
      mainInteractive: Boolean,
      streams: SystemStreams,
      env: Map[String, String],
      setIdle: Boolean => Unit,
      userSpecifiedProperties: Map[String, String],
      initialSystemProperties: Map[String, String],
      systemExit: Int => Nothing
  ): (Boolean, T)

}

object Server {
  def computeProcessId() = {
    java.lang.Long.toHexString(scala.util.Random.nextLong()) +
      "-pid" +
      ProcessHandle.current().pid()
  }
  def checkProcessIdFile(processIdFile: os.Path, processId: String): Option[String] = {
    Try(os.read(processIdFile)) match {
      case scala.util.Failure(e) => Some(s"processId file missing")

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
          case e => "<unknown>"
        }

      def activeTaskPrefix = s"Another Mill process is running '$activeTaskString',"

      Using.resource {
        val tryLocked = outLock.tryLock()
        if (tryLocked.isLocked()) tryLocked
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
