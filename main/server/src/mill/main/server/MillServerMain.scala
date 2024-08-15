package mill.main.server

import sun.misc.{Signal, SignalHandler}

import java.io._
import java.net.Socket
import scala.jdk.CollectionConverters._
import org.newsclub.net.unix.AFUNIXServerSocket
import org.newsclub.net.unix.AFUNIXSocketAddress
import mill.main.client._
import mill.api.{SystemStreams, internal}
import mill.main.client.ProxyStream.Output
import mill.main.client.lock.{Lock, Locks}

import scala.util.Try


abstract class MillServerMain[T](
                                  serverDir: os.Path,
                                  interruptServer: () => Unit,
                                  acceptTimeoutMillis: Int,
                                  locks: Locks
) {
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

  val originalStdout = System.out
  val serverId = scala.util.Random.nextLong().toString
  def serverLog(s: String) = os.write.append(serverDir / ServerFiles.serverLog, s + "\n")
  def run(): Unit = {
    val initialSystemProperties = sys.props.toMap

    Server.tryLockBlock(locks.processLock) {

      watchServerIdFile()

      var running = true
      while (running) {

        serverLog("listening on socket")
        val serverSocket = bindSocket()
        val sockOpt = interruptWithTimeout(() => serverSocket.close(), () => serverSocket.accept())

        sockOpt match {
          case None => running = false
          case Some(sock) =>
            try {
              serverLog("handling run")
              try handleRun(sock, initialSystemProperties)
              catch { case e: Throwable => serverLog(e + "\n" + e.getStackTrace.mkString("\n"))}
              finally sock.close();
            } finally serverSocket.close()
        }
      }
    }.getOrElse(throw new Exception("Mill server process already present, exiting"))
  }

  def bindSocket() = {
    val socketPath = os.Path(ServerFiles.pipe(serverDir.toString()))
    os.remove.all(socketPath)

    // Use relative path because otherwise the full path might be too long for the socket API
    val addr = AFUNIXSocketAddress.of(socketPath.relativeTo(os.pwd).toNIO.toFile)
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

  def handleRun(clientSocket: Socket, initialSystemProperties: Map[String, String]): Unit

  def watchServerIdFile() = {
    os.write.over(serverDir / ServerFiles.serverId, serverId)
    val serverIdThread = new Thread(
      () => {
        while (true){
          Thread.sleep(100)
          Try(os.read(serverDir / ServerFiles.serverId)).toOption match{
            case None =>
              serverLog("serverId file missing, exiting")
              System.exit(0)
            case Some(s) =>
              if (s != serverId){
                serverLog(s"serverId file contents $s does not match serverId $serverId, exiting")
                System.exit(0)
              }
          }
        }
      },
      "Server ID Checker Thread"
    )
    serverIdThread.start()
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
      "MillSocketTimeoutInterruptThread",
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
