package mill.clientserver

import java.io._
import java.net.Socket

import org.scalasbt.ipcsocket.{UnixDomainServerSocket, UnixDomainSocket}

trait ServerMain[T]{
  def main(args0: Array[String]): Unit = {
    new Server(
      args0(0),
      this,
      () => System.exit(0),
      60000,
      new FileLocks(args0(0))
    ).run()
  }
  var stateCache = Option.empty[T]
  def main0(args: Array[String],
            stateCache: Option[T],
            mainInteractive: Boolean,
            stdin: InputStream,
            stdout: PrintStream,
            stderr: PrintStream): (Boolean, Option[T])
}


class Server[T](lockBase: String,
                sm: ServerMain[T],
                interruptServer: () => Unit,
                acceptTimeout: Int,
                locks: Locks) extends ClientServer(lockBase){

  val originalStdout = System.out
  def run() = {
    locks.processLock.tryLockBlock{
      var running = true
      while (running) locks.serverLock.lockBlock{
        new File(ioPath).delete()
        val ioSocket = new UnixDomainServerSocket(ioPath)
        val sockOpt = ClientServer.interruptWith(
          acceptTimeout,
          new UnixDomainSocket(ioPath).close(),
          ioSocket.accept()
        )

        sockOpt match{
          case None => running = false
          case Some(sock) =>
            try handleRun(sock)
            catch{case e: Throwable => e.printStackTrace(originalStdout) }
        }
      }
    }.getOrElse(throw new Exception("PID already present"))
  }

  def handleRun(clientSocket: Socket) = {

    val currentOutErr = clientSocket.getOutputStream
    val socketIn = clientSocket.getInputStream
    val argStream = new FileInputStream(runFile)
    val (interactive, args) = ClientServer.parseArgs(argStream)
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
          stdout, stderr
        )

        sm.stateCache = newStateCache
        java.nio.file.Files.write(
          java.nio.file.Paths.get(exitCodePath),
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
    clientSocket.close()
  }
}
case class WatchInterrupted[T](stateCache: Option[T]) extends Exception