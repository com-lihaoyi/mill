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
            watchInterrupted: () => Boolean,
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
      println("Server Process Lock")
      var running = true
      while (running) locks.serverLock.lockBlock{
        println("Server Lock")
        new File(ioPath).delete()
        println("Server Accept Socket")
        val ioSocket = new UnixDomainServerSocket(ioPath)
        val sockOpt = ClientServer.interruptWith(
          acceptTimeout,
          {
            println("Server Socket Timing Out Close")
            try new UnixDomainSocket(ioPath).close()
            catch{case e: Throwable => }
          }
        ){
          ioSocket.accept()
        }
        sockOpt match{
          case None => running = false
          case Some(sock) =>
            println("Server Handle Run")
            try handleRun(sock)
            catch{case e: Throwable => e.printStackTrace(originalStdout) }
        }
        println("Server Unlock")
      }
      println("Server Process Unlock")
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
        val (_, newStateCache) = sm.main0(
          args,
          sm.stateCache,
          interactive,
          () => !locks.clientLock.probe(),
          socketIn,
          stdout, stderr
        )

        sm.stateCache = newStateCache
      } catch{case WatchInterrupted(sc: Option[T]) =>
        sm.stateCache = sc
      } finally{
        done = true
      }
    )

    t.start()
    println("Server Poll Client/Done")
    // We cannot simply use Lock#await here, because the filesystem doesn't
    // realize the clientLock/serverLock are held by different threads in the
    // two processes and gives a spurious deadlock error
    while(!done && !locks.clientLock.probe()) {
      Thread.sleep(3)
    }

    t.interrupt()
    t.stop()
    println("Server Socket Close")
    clientSocket.close()
  }
}
case class WatchInterrupted[T](stateCache: Option[T]) extends Exception