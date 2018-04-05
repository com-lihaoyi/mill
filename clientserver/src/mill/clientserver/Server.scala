package mill.clientserver

import java.io._
import java.net.Socket

import scala.collection.JavaConverters._
import org.scalasbt.ipcsocket._

trait ServerMain[T]{
  def main(args0: Array[String]): Unit = {
    new Server(
      args0(0),
      this,
      () => System.exit(0),
      300000,
      Locks.files(args0(0))
    ).run()
  }
  var stateCache = Option.empty[T]
  def main0(args: Array[String],
            stateCache: Option[T],
            mainInteractive: Boolean,
            stdin: InputStream,
            stdout: PrintStream,
            stderr: PrintStream,
            env : Map[String, String]): (Boolean, Option[T])
}


class Server[T](lockBase: String,
                sm: ServerMain[T],
                interruptServer: () => Unit,
                acceptTimeout: Int,
                locks: Locks) {

  val originalStdout = System.out
  def run() = {
    Server.tryLockBlock(locks.processLock){
      var running = true
      while (running) {
        Server.lockBlock(locks.serverLock){
          val (serverSocket, socketClose) = if (ClientServer.isWindows) {
            val socketName = ClientServer.WIN32_PIPE_PREFIX + new File(lockBase).getName
            (new Win32NamedPipeServerSocket(socketName), () => new Win32NamedPipeSocket(socketName).close())
          } else {
            val socketName = lockBase + "/io"
            new File(socketName).delete()
            (new UnixDomainServerSocket(socketName), () => new UnixDomainSocket(socketName).close())
          }

          val sockOpt = Server.interruptWith(
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
    val socketIn = clientSocket.getInputStream
    val argStream = new FileInputStream(lockBase + "/run")
    val interactive = argStream.read() != 0;
    val args = ClientServer.parseArgs(argStream)
    val env = ClientServer.parseMap(argStream)
    argStream.close()

    var done = false
    val t = new Thread(() =>

      try {
        val stdout = new PrintStream(new ProxyOutputStream(currentOutErr, 0), true)
        val stderr = new PrintStream(new ProxyOutputStream(currentOutErr, 1), true)
        val (result, newStateCache) = sm.main0(
          args,
          sm.stateCache,
          interactive,
          socketIn,
          stdout,
          stderr,
          env.asScala.toMap
        )

        sm.stateCache = newStateCache
        java.nio.file.Files.write(
          java.nio.file.Paths.get(lockBase + "/exitCode"),
          (if (result) 0 else 1).toString.getBytes
        )
      } catch{case WatchInterrupted(sc: Option[T]) =>
        sm.stateCache = sc
      } finally{
        done = true
      }
    )

    t.start()
    // We cannot simply use Lock#await here, because the filesystem doesn't
    // realize the clientLock/serverLock are held by different threads in the
    // two processes and gives a spurious deadlock error
    while(!done && !locks.clientLock.probe()) {
      Thread.sleep(3)
    }

    if (!done) interruptServer()

    t.interrupt()
    t.stop()

    if (ClientServer.isWindows) {
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
  def interruptWith[T](millis: Int, close: => Unit, t: => T): Option[T] = {
    @volatile var interrupt = true
    @volatile var interrupted = false
    new Thread(() => {
      Thread.sleep(millis)
      if (interrupt) {
        interrupted = true
        close
      }
    }).start()

    try {
      val res =
        try Some(t)
        catch {case e: Throwable => None}

      if (interrupted) None
      else res

    } finally {
      interrupt = false
    }
  }
}

class ProxyOutputStream(x: => java.io.OutputStream,
                        key: Int) extends java.io.OutputStream  {
  override def write(b: Int) = x.synchronized{
    x.write(key)
    x.write(b)
  }
}
class ProxyInputStream(x: => java.io.InputStream) extends java.io.InputStream{
  def read() = x.read()
  override def read(b: Array[Byte], off: Int, len: Int) = x.read(b, off, len)
  override def read(b: Array[Byte]) = x.read(b)
}
case class WatchInterrupted[T](stateCache: Option[T]) extends Exception
