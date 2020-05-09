package mill.main

import java.io._
import java.net.Socket

import mill.MillMain

import scala.collection.JavaConverters._
import org.scalasbt.ipcsocket._
import mill.main.client._
import mill.eval.Evaluator
import mill.api.DummyInputStream
import sun.misc.{Signal, SignalHandler}

trait MillServerMain[T]{
  var stateCache = Option.empty[T]
  def main0(args: Array[String],
            stateCache: Option[T],
            mainInteractive: Boolean,
            stdin: InputStream,
            stdout: PrintStream,
            stderr: PrintStream,
            env : Map[String, String],
            setIdle: Boolean => Unit,
            systemProperties: Map[String, String]): (Boolean, Option[T])
}

object MillServerMain extends mill.main.MillServerMain[Evaluator.State]{
  def main(args0: Array[String]): Unit = {
    // Disable SIGINT interrupt signal in the Mill server.
    //
    // This gets passed through from the client to server whenever the user
    // hits `Ctrl-C`, which by default kills the server, which defeats the purpose
    // of running a background server. Furthermore, the background server already
    // can detect when the Mill client goes away, which is necessary to handle
    // the case when a Mill client that did *not* spawn the server gets `CTRL-C`ed
    Signal.handle(new Signal("INT"), new SignalHandler () {
      def handle(sig: Signal) = {} // do nothing
    })
    new Server(
      lockBase = args0(0),
      this,
      () => System.exit(MillClientMain.ExitServerCodeWhenIdle()),
      300000,
      mill.main.client.Locks.files(args0(0))
    ).run()
  }

  def main0(args: Array[String],
            stateCache: Option[Evaluator.State],
            mainInteractive: Boolean,
            stdin: InputStream,
            stdout: PrintStream,
            stderr: PrintStream,
            env : Map[String, String],
            setIdle: Boolean => Unit,
            systemProperties: Map[String, String]): (Boolean, Option[Evaluator.State]) = {
    MillMain.main0(
      args,
      stateCache,
      mainInteractive = mainInteractive,
      DummyInputStream,
      stdout,
      stderr,
      env,
      setIdle = setIdle,
      systemProperties
    )
  }
}


class Server[T](lockBase: String,
                sm: MillServerMain[T],
                interruptServer: () => Unit,
                acceptTimeout: Int,
                locks: Locks) {

  val originalStdout = System.out
  def run() = {
    Server.tryLockBlock(locks.processLock){
      var running = true
      while (running) {
        Server.lockBlock(locks.serverLock){
          val (serverSocket, socketClose) = if (Util.isWindows) {
            val socketName = Util.WIN32_PIPE_PREFIX + new File(lockBase).getName
            (new Win32NamedPipeServerSocket(socketName), () => new Win32NamedPipeSocket(socketName).close())
          } else {
            val socketName = lockBase + "/io"
            new File(socketName).delete()
            (new UnixDomainServerSocket(socketName), () => new UnixDomainSocket(socketName).close())
          }

          val sockOpt = Server.interruptWith(
            "MillSocketTimeoutInterruptThread",
            acceptTimeout,
            socketClose(),
            serverSocket.accept()
          )

          sockOpt match{
            case None => running = false
            case Some(sock) =>
              try {
                handleRun(sock)
                serverSocket.close()
              }
              catch{case e: Throwable => e.printStackTrace(originalStdout) }
          }
        }
        // Make sure you give an opportunity for the client to probe the lock
        // and realize the server has released it to signal completion
        Thread.sleep(10)
      }
    }.getOrElse(throw new Exception("PID already present"))
  }

  def handleRun(clientSocket: Socket) = {

    val currentOutErr = clientSocket.getOutputStream
    val stdout = new PrintStream(new ProxyOutputStream(currentOutErr, 1), true)
    val stderr = new PrintStream(new ProxyOutputStream(currentOutErr, -1), true)
    val socketIn = clientSocket.getInputStream
    val argStream = new FileInputStream(lockBase + "/run")
    val interactive = argStream.read() != 0
    val clientMillVersion = Util.readString(argStream)
    val serverMillVersion = sys.props("MILL_VERSION")
    if (clientMillVersion != serverMillVersion) {
      stderr.println(s"Mill version changed ($serverMillVersion -> $clientMillVersion), re-starting server")
      java.nio.file.Files.write(
        java.nio.file.Paths.get(lockBase + "/exitCode"),
        s"${MillClientMain.ExitServerCodeWhenVersionMismatch()}".getBytes()
      )
      System.exit(MillClientMain.ExitServerCodeWhenVersionMismatch())
    }
    val args = Util.parseArgs(argStream)
    val env = Util.parseMap(argStream)
    val systemProperties = Util.parseMap(argStream)
    argStream.close()

    @volatile var done = false
    @volatile var idle = false
    val t = new Thread(() =>
      try {
        val (result, newStateCache) = sm.main0(
          args,
          sm.stateCache,
          interactive,
          socketIn,
          stdout,
          stderr,
          env.asScala.toMap,
          idle = _,
          systemProperties.asScala.toMap
        )

        sm.stateCache = newStateCache
        java.nio.file.Files.write(
          java.nio.file.Paths.get(lockBase + "/exitCode"),
          (if (result) 0 else 1).toString.getBytes
        )
      } finally{
        done = true
        idle = true
      },
      "MillServerActionRunner"
    )
    t.start()
    // We cannot simply use Lock#await here, because the filesystem doesn't
    // realize the clientLock/serverLock are held by different threads in the
    // two processes and gives a spurious deadlock error
    while(!done && !locks.clientLock.probe()) Thread.sleep(3)

    if (!idle) interruptServer()


    t.interrupt()
    // Try to give thread a moment to stop before we kill it for real
    Thread.sleep(5)
    try t.stop()
    catch{case e: java.lang.Error if e.getMessage.contains("Cleaner terminated abnormally") =>
      // ignore this error and do nothing; seems benign
    }

    // flush before closing the socket
    System.out.flush()
    System.err.flush()

    if (Util.isWindows) {
      // Closing Win32NamedPipeSocket can often take ~5s
      // It seems OK to exit the client early and subsequently
      // start up mill client again (perhaps closing the server
      // socket helps speed up the process).
      val t = new Thread(() => clientSocket.close())
      t.setDaemon(true)
      t.start()
    } else clientSocket.close()
  }
}
object Server{
  def lockBlock[T](lock: Lock)(t: => T): T = {
    val l = lock.lock()
    try t
    finally l.release()
  }
  def tryLockBlock[T](lock: Lock)(t: => T): Option[T] = {
    lock.tryLock() match{
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
        catch{ case t: InterruptedException => /* Do Nothing */ }
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
        catch {case e: Throwable => None}

      if (interrupted) None
      else res

    } finally {
      thread.interrupt()
      interrupt = false
    }
  }
}


