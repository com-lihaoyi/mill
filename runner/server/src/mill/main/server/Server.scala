package mill.main.server

import mill.api.SystemStreams
import mill.constants.ProxyStream.Output
import mill.client.lock.{Lock, Locks}
import mill.client.*
import mill.constants.ServerFiles
import mill.constants.InputPumper
import mill.constants.ProxyStream

import java.io.*
import java.net.{InetAddress, Socket}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Models a long-lived server that receives requests from a client and calls a [[main0]]
 * method to run the commands in-process. Provides the command args, env variables,
 * JVM properties, wrapped input/output streams, and other metadata related to the
 * client command
 */
abstract class Server[T](
    serverDir: os.Path,
    acceptTimeoutMillis: Int,
    locks: Locks,
    testLogEvenWhenServerIdWrong: Boolean = false
) {

  @volatile var running = true
  def exitServer(): Unit = running = false
  var stateCache = stateCache0
  def stateCache0: T

  val processId: String = Server.computeProcessId()
  def serverLog0(s: String): Unit = {
    if (os.exists(serverDir) || testLogEvenWhenServerIdWrong) {
      os.write.append(serverDir / ServerFiles.serverLog, s"$s\n", createFolders = true)
    }
  }

  def serverLog(s: String): Unit = serverLog0(s"$processId $s")

  def run(): Unit = {
    serverLog("running server in " + serverDir)
    val initialSystemProperties = sys.props.toMap

    try {
      Server.tryLockBlock(locks.processLock) {
        serverLog("server file locked")
        Server.watchProcessIdFile(
          serverDir / ServerFiles.processId,
          processId,
          running = () => running,
          exit = msg => {
            serverLog(msg)
            exitServer()
          }
        )
        val serverSocket = new java.net.ServerSocket(0, 0, InetAddress.getByName(null))
        os.write.over(serverDir / ServerFiles.socketPort, serverSocket.getLocalPort.toString)
        serverLog("listening on port " + serverSocket.getLocalPort)
        while (
          running && {
            interruptWithTimeout(() => serverSocket.close(), () => serverSocket.accept()) match {
              case None => false
              case Some(sock) =>
                serverLog("handling run")
                try handleRun(sock, initialSystemProperties)
                catch {
                  case e: Throwable =>
                    serverLog(e.toString + "\n" + e.getStackTrace.mkString("\n"))
                } finally sock.close();
                true
            }
          }
        ) ()
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

  def handleRun(clientSocket: Socket, initialSystemProperties: Map[String, String]): Unit = {

    val currentOutErr = clientSocket.getOutputStream
    try {
      val stdout = new PrintStream(new Output(currentOutErr, ProxyStream.OUT), true)
      val stderr = new PrintStream(new Output(currentOutErr, ProxyStream.ERR), true)

      // Proxy the input stream through a pair of Piped**putStream via a pumper,
      // as the `UnixDomainSocketInputStream` we get directly from the socket does
      // not properly implement `available(): Int` and thus messes up polling logic
      // that relies on that method
      val proxiedSocketInput = proxyInputStreamThroughPumper(clientSocket.getInputStream)

      val argStream = os.read.inputStream(serverDir / ServerFiles.runArgs)
      val interactive = argStream.read() != 0
      val clientMillVersion = ClientUtil.readString(argStream)
      val serverMillVersion = BuildInfo.millVersion
      if (clientMillVersion != serverMillVersion) {
        stderr.println(
          s"Mill version changed ($serverMillVersion -> $clientMillVersion), re-starting server"
        )
        os.write(
          serverDir / ServerFiles.exitCode,
          ClientUtil.ExitServerCodeWhenVersionMismatch().toString.getBytes()
        )
        System.exit(ClientUtil.ExitServerCodeWhenVersionMismatch())
      }
      val args = ClientUtil.parseArgs(argStream)
      val env = ClientUtil.parseMap(argStream)
      serverLog("args " + upickle.default.write(args))
      serverLog("env " + upickle.default.write(env.asScala))
      val userSpecifiedProperties = ClientUtil.parseMap(argStream)
      argStream.close()

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
              userSpecifiedProperties.asScala.toMap,
              initialSystemProperties,
              systemExit = exitCode => {
                os.write.over(serverDir / ServerFiles.exitCode, exitCode.toString)
                sys.exit(exitCode)
              }
            )

            stateCache = newStateCache
            val exitCode = if (result) "0" else "1"
            serverLog("exitCode " + exitCode)
            os.write.over(serverDir / ServerFiles.exitCode, exitCode)
          } finally {
            done = true
            idle = true
          },
        "MillServerActionRunner"
      )
      t.start()

      // We cannot simply use Lock#await here, because the filesystem doesn't
      // realize the clientLock/serverLock are held by different threads in the
      // two processes and gives a spurious deadlock error
      while (!done && !locks.clientLock.probe()) Thread.sleep(1)

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

    } finally ProxyStream.sendEnd(currentOutErr) // Send a termination
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
      "Process ID Checker Thread: " + processIdFile
    )
    processIdThread.setDaemon(true)
    processIdThread.start()
  }

  def tryLockBlock[T](lock: Lock)(t: => T): Option[T] = {
    lock.tryLock() match {
      case null => None
      case l =>
        if (l.isLocked) {
          try Some(t)
          finally l.release()
        } else {
          None
        }
    }
  }
}
