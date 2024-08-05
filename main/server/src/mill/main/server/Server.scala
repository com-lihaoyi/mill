package mill.main.server

import java.io._
import java.net.Socket
import scala.jdk.CollectionConverters._
import org.newsclub.net.unix.AFUNIXServerSocket
import org.newsclub.net.unix.AFUNIXSocketAddress
import mill.main.client._
import mill.api.SystemStreams
import mill.main.client.ProxyStream.Output
import mill.main.client.lock.{Lock, Locks}

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

  val serverId: String = java.lang.Long.toHexString(scala.util.Random.nextLong())
  def serverLog0(s: String): Unit = {
    if (running && (testLogEvenWhenServerIdWrong || checkServerIdFile().isEmpty)) {
      os.write.append(serverDir / ServerFiles.serverLog, s"$s\n", createFolders = true)
    }
  }

  def serverLog(s: String): Unit = serverLog0(s"$serverId $s")

  def run(): Unit = {
    serverLog("running server in " + serverDir)
    val initialSystemProperties = sys.props.toMap

    try Server.tryLockBlock(locks.processLock) {
        watchServerIdFile()

        while (
          running && {
            val serverSocket = bindSocket()
            try
              interruptWithTimeout(() => serverSocket.close(), () => serverSocket.accept()) match {
                case None => false
                case Some(sock) =>
                  serverLog("handling run")
                  try handleRun(sock, initialSystemProperties)
                  catch {
                    case e: Throwable => serverLog(e.toString + "\n" + e.getStackTrace.mkString("\n"))
                  } finally sock.close();
                  true
              }
            finally serverSocket.close()
          }
        ) ()

      }.getOrElse(throw new Exception("Mill server process already present"))
    finally exitServer()
  }

  def bindSocket(): AFUNIXServerSocket = {
    val socketPath = os.Path(ServerFiles.pipe(serverDir.toString()))
    os.remove.all(socketPath)

    val relFile = socketPath.relativeTo(os.pwd).toNIO.toFile
    serverLog("listening on socket " + relFile)
    // Use relative path because otherwise the full path might be too long for the socket API
    val addr = AFUNIXSocketAddress.of(relFile)
    AFUNIXServerSocket.bindOn(addr)
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

  def watchServerIdFile(): Unit = {
    os.write.over(serverDir / ServerFiles.serverId, serverId)

    val serverIdThread = new Thread(
      () =>
        while (running) {
          checkServerIdFile() match {
            case None => Thread.sleep(100)
            case Some(msg) =>
              serverLog(msg)
              exitServer()
          }
        },
      "Server ID Checker Thread"
    )
    serverIdThread.start()
  }
  def checkServerIdFile(): Option[String] = {
    Try(os.read(serverDir / ServerFiles.serverId)) match {
      case scala.util.Failure(e) => Some(s"serverId file missing")

      case scala.util.Success(s) =>
        Option.when(s != serverId) {
          s"serverId file contents $s does not match serverId $serverId"
        }
    }

  }

  def interruptWithTimeout[T](close: () => Unit, t: () => T): Option[T] = {
    @volatile var interrupt = true
    @volatile var interrupted = false
    val thread = new Thread(
      () => {
        try Thread.sleep(acceptTimeoutMillis)
        catch { case t: InterruptedException => /* Do Nothing */ }
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
      thread.interrupt()
      interrupt = false
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
      val clientMillVersion = Util.readString(argStream)
      val serverMillVersion = BuildInfo.millVersion
      if (clientMillVersion != serverMillVersion) {
        stderr.println(
          s"Mill version changed ($serverMillVersion -> $clientMillVersion), re-starting server"
        )
        os.write(
          serverDir / ServerFiles.exitCode,
          Util.ExitServerCodeWhenVersionMismatch().toString.getBytes()
        )
        System.exit(Util.ExitServerCodeWhenVersionMismatch())
      }
      val args = Util.parseArgs(argStream)
      val env = Util.parseMap(argStream)
      serverLog("args " + upickle.default.write(args))
      serverLog("env " + upickle.default.write(env.asScala))
      val userSpecifiedProperties = Util.parseMap(argStream)
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
            serverLog("exitCode " + ServerFiles.exitCode)
            os.write.over(serverDir / ServerFiles.exitCode, if (result) "0" else "1")
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
      while (!done && !locks.clientLock.probe()) Thread.sleep(3)

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

  def lockBlock[T](lock: Lock)(t: => T): T = {
    val l = lock.lock()
    try t
    finally l.release()
  }

  def tryLockBlock[T](lock: Lock)(t: => T): Option[T] = {
    lock.tryLock() match {
      case null => None
      case l =>
        try Some(t)
        finally l.release()
    }
  }
}
