package mill.runner

import sun.misc.{Signal, SignalHandler}

import java.io._
import java.net.Socket
import scala.jdk.CollectionConverters._
import org.newsclub.net.unix.AFUNIXServerSocket
import org.newsclub.net.unix.AFUNIXSocketAddress
import mill.BuildInfo
import mill.main.client._
import mill.api.internal
import mill.main.client.lock.{Lock, Locks}
import mill.util.SystemStreams

@internal
trait MillServerMain[T] {
  def stateCache0: T
  var stateCache = stateCache0
  def main0(
      args: Array[String],
      stateCache: T,
      mainInteractive: Boolean,
      streams: SystemStreams,
      env: Map[String, String],
      setIdle: Boolean => Unit,
      systemProperties: Map[String, String],
      initialSystemProperties: Map[String, String]
  ): (Boolean, T)
}

@internal
object MillServerMain extends MillServerMain[RunnerState] {
  def stateCache0 = RunnerState.empty
  def main(args0: Array[String]): Unit = {
    // Disable SIGINT interrupt signal in the Mill server.
    //
    // This gets passed through from the client to server whenever the user
    // hits `Ctrl-C`, which by default kills the server, which defeats the purpose
    // of running a background server. Furthermore, the background server already
    // can detect when the Mill client goes away, which is necessary to handle
    // the case when a Mill client that did *not* spawn the server gets `CTRL-C`ed
    Signal.handle(
      new Signal("INT"),
      new SignalHandler() {
        def handle(sig: Signal) = {} // do nothing
      }
    )
    new Server(
      lockBase = args0(0),
      this,
      () => System.exit(MillClientMain.ExitServerCodeWhenIdle()),
      acceptTimeoutMillis = 5 * 60 * 1000, // 5 minutes
      Locks.files(args0(0))
    ).run()
  }

  def main0(
      args: Array[String],
      stateCache: RunnerState,
      mainInteractive: Boolean,
      streams: SystemStreams,
      env: Map[String, String],
      setIdle: Boolean => Unit,
      userSpecifiedProperties: Map[String, String],
      initialSystemProperties: Map[String, String]
  ): (Boolean, RunnerState) = {
    MillMain.main0(
      args,
      stateCache,
      mainInteractive = mainInteractive,
      streams,
      env,
      setIdle = setIdle,
      userSpecifiedProperties0 = userSpecifiedProperties,
      initialSystemProperties = initialSystemProperties
    )
  }
}

class Server[T](
    lockBase: String,
    sm: MillServerMain[T],
    interruptServer: () => Unit,
    acceptTimeoutMillis: Int,
    locks: Locks
) {

  val originalStdout = System.out
  def run() = {
    val initialSystemProperties = sys.props.toMap
    Server.tryLockBlock(locks.processLock) {
      var running = true
      while (running) {
        Server.lockBlock(locks.serverLock) {
          val socketName =
            lockBase + "/mill-" + Util.md5hex(new File(lockBase).getCanonicalPath()) + "-io"
          new File(socketName).delete()
          val addr = AFUNIXSocketAddress.of(new File(socketName))
          val serverSocket = AFUNIXServerSocket.bindOn(addr)
          val socketClose = () => serverSocket.close()

          val sockOpt = Server.interruptWith(
            "MillSocketTimeoutInterruptThread",
            acceptTimeoutMillis,
            socketClose(),
            serverSocket.accept()
          )

          sockOpt match {
            case None => running = false
            case Some(sock) =>
              try {
                handleRun(sock, initialSystemProperties)
                serverSocket.close()
              } catch { case e: Throwable => e.printStackTrace(originalStdout) }
          }
        }
        // Make sure you give an opportunity for the client to probe the lock
        // and realize the server has released it to signal completion
        Thread.sleep(10)
      }
    }.getOrElse(throw new Exception("PID already present"))
  }

  def proxyInputStreamThroughPumper(in: InputStream) = {
    val pipedInput = new PipedInputStream()
    val pipedOutput = new PipedOutputStream()
    pipedOutput.connect(pipedInput)
    val pumper = new InputPumper(in, pipedOutput, false)
    val pumperThread = new Thread(pumper, "proxyInputStreamThroughPumper")
    pumperThread.setDaemon(true)
    pumperThread.start()
    pipedInput
  }
  def handleRun(clientSocket: Socket, initialSystemProperties: Map[String, String]) = {

    val currentOutErr = clientSocket.getOutputStream
    val stdout = new PrintStream(new ProxyOutputStream(currentOutErr, 1), true)
    val stderr = new PrintStream(new ProxyOutputStream(currentOutErr, -1), true)
    // Proxy the input stream through a pair of Piped**putStream via a pumper,
    // as the `UnixDomainSocketInputStream` we get directly from the socket does
    // not properly implement `available(): Int` and thus messes up polling logic
    // that relies on that method
    val proxiedSocketInput = proxyInputStreamThroughPumper(clientSocket.getInputStream)

    val argStream = new FileInputStream(lockBase + "/run")
    val interactive = argStream.read() != 0
    val clientMillVersion = Util.readString(argStream)
    val serverMillVersion = BuildInfo.millVersion
    if (clientMillVersion != serverMillVersion) {
      stderr.println(
        s"Mill version changed ($serverMillVersion -> $clientMillVersion), re-starting server"
      )
      java.nio.file.Files.write(
        java.nio.file.Paths.get(lockBase + "/exitCode"),
        s"${MillClientMain.ExitServerCodeWhenVersionMismatch()}".getBytes()
      )
      System.exit(MillClientMain.ExitServerCodeWhenVersionMismatch())
    }
    val args = Util.parseArgs(argStream)
    val env = Util.parseMap(argStream)
    val userSpecifiedProperties = Util.parseMap(argStream)
    argStream.close()

    @volatile var done = false
    @volatile var idle = false
    val t = new Thread(
      () =>
        try {
          val (result, newStateCache) = sm.main0(
            args,
            sm.stateCache,
            interactive,
            new SystemStreams(stdout, stderr, proxiedSocketInput),
            env.asScala.toMap,
            idle = _,
            userSpecifiedProperties.asScala.toMap,
            initialSystemProperties
          )

          sm.stateCache = newStateCache
          java.nio.file.Files.write(
            java.nio.file.Paths.get(lockBase + "/exitCode"),
            (if (result) 0 else 1).toString.getBytes
          )
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

    if (!idle) interruptServer()

    t.interrupt()
    // Try to give thread a moment to stop before we kill it for real
    Thread.sleep(5)
    try t.stop()
    catch {
      case e: java.lang.Error if e.getMessage.contains("Cleaner terminated abnormally") =>
      // ignore this error and do nothing; seems benign
    }

    // flush before closing the socket
    System.out.flush()
    System.err.flush()

    clientSocket.close()
  }
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

  def interruptWith[T](threadName: String, millis: Int, close: => Unit, t: => T): Option[T] = {
    @volatile var interrupt = true
    @volatile var interrupted = false
    val thread = new Thread(
      () => {
        try Thread.sleep(millis)
        catch { case t: InterruptedException => /* Do Nothing */ }
        if (interrupt) {
          interrupted = true
          close
        }
      },
      threadName
    )

    thread.start()
    try {
      val res =
        try Some(t)
        catch { case e: Throwable => None }

      if (interrupted) None
      else res

    } finally {
      thread.interrupt()
      interrupt = false
    }
  }
}
