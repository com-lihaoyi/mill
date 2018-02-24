package mill.clientserver

import java.io._

import org.scalasbt.ipcsocket.UnixDomainSocket

object Client{
  def WithLock[T](index: Int)(f: String => T): T = {
    val lockBase = "out/mill-worker-" + index
    new java.io.File(lockBase).mkdirs()
    val lockFile = new RandomAccessFile(lockBase+ "/clientLock", "rw")
    val channel = lockFile.getChannel
    channel.tryLock() match{
      case null =>
        lockFile.close()
        channel.close()
        if (index < 5) WithLock(index + 1)(f)
        else throw new Exception("Reached max process limit: " + 5)
      case locked =>
        try f(lockBase)
        finally{
          locked.release()
          lockFile.close()
          channel.close()
        }
    }
  }
}

class Client(lockBase: String,
             initServer: () => Unit,
             locks: Locks,
             stdin: InputStream,
             stdout: OutputStream,
             stderr: OutputStream) extends ClientServer(lockBase){
  def run(args: Array[String]) = {
    println("Client Run")
    val f = new FileOutputStream(runFile)
    ClientServer.writeArgs(System.console() != null, args, f)
    f.close()
    if (locks.processLock.probe()) initServer()
    while(locks.processLock.probe()) Thread.sleep(3)

    println("Client Connect Socket")

    val ioSocket = ClientServer.retry(1000, new UnixDomainSocket(ioPath))
    println("Client Connected Socket")
    val outErr = ioSocket.getInputStream
    val in = ioSocket.getOutputStream
    val outPump = new ClientOutputPumper(outErr, stdout, stderr)
    val inPump = new ClientInputPumper(stdin, in)
    val outThread = new Thread(outPump)
    outThread.setDaemon(true)
    val inThread = new Thread(inPump)
    inThread.setDaemon(true)
    outThread.start()
    inThread.start()
    println("Client Await Server Lock")

    locks.serverLock.await()
    println("Client End")

  }
}
