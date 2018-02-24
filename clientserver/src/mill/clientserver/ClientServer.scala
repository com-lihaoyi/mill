package mill.clientserver

import java.io.{FileInputStream, InputStream, OutputStream, RandomAccessFile}
import java.nio.channels.FileChannel

import scala.annotation.tailrec

class ClientServer(lockBase: String){
  val ioPath = lockBase + "/io"
  val logFile = new java.io.File(lockBase + "/log")
  val runFile = new java.io.File(lockBase + "/run")
}

object ClientServer{
  def parseArgs(argStream: InputStream) = {
    val interactive = argStream.read() != 0
    val argsLength = argStream.read()
    val args = Array.fill(argsLength){
      val n = argStream.read()
      val arr = new Array[Byte](n)
      argStream.read(arr)
      new String(arr)
    }
    (interactive, args)
  }
  def writeArgs(interactive: Boolean, args: Array[String], argStream: OutputStream) = {
    argStream.write(if (interactive) 1 else 0)
    argStream.write(args.length)
    var i = 0
    while (i < args.length){
      argStream.write(args(i).length)
      argStream.write(args(i).getBytes)
      i += 1
    }
  }
  @tailrec def retry[T](millis: Long, t: => T): T = {
    val current = System.currentTimeMillis()
    val res =
      try Some(t)
      catch{case e: Throwable if System.currentTimeMillis() < current + millis =>
        None
      }
    res match{
      case Some(t) => t
      case None =>
        Thread.sleep(1)
        retry(millis - (System.currentTimeMillis() - current), t)
    }
  }

  def interruptWith[T](millis: Int, close: => Unit)(t: => T): Option[T] = {
    @volatile var interrupt = true
    @volatile var interrupted = false
    new Thread(() => {
      Thread.sleep(millis)
      if (interrupt) {
        close
        interrupted = true
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

  def polling[T](probe: => Boolean, cb: () => Unit)(t: => T): T = {
    var probing = true
    val probeThread = new Thread(() => while(probing){
      if (probe){
        probing = false
        cb()
      }
      Thread.sleep(1000)
    })
    probeThread.start()
    try t
    finally probing = false
  }
}
object ProxyOutputStream{
  val lock = new Object
}
class ProxyOutputStream(x: => java.io.OutputStream,
                        key: Int) extends java.io.OutputStream  {
  override def write(b: Int) = ProxyOutputStream.lock.synchronized{
    x.write(key)
    x.write(b)
  }
}
class ProxyInputStream(x: => java.io.InputStream) extends java.io.InputStream{
  def read() = x.read()
  override def read(b: Array[Byte], off: Int, len: Int) = x.read(b, off, len)
  override def read(b: Array[Byte]) = x.read(b)
}

class ClientInputPumper(src: InputStream, dest: OutputStream) extends Runnable{
  var running = true
  def run() = {
    val buffer = new Array[Byte](1024)
    while(running){
      val n = src.read(buffer)
      if (n == -1) running = false
      else {
        dest.write(buffer, 0, n)
        dest.flush()
      }
    }
  }

}
class ClientOutputPumper(src: InputStream, dest1: OutputStream, dest2: OutputStream) extends Runnable{
  var running = true
  def run() = {
    val buffer = new Array[Byte](1024)
    var state = 0
    while(running){
      val n = src.read(buffer)
      if (n == -1) running = false
      else {
        var i = 0
        while (i < n){
          state match{
            case 0 => state = buffer(i) + 1
            case 1 =>
              dest1.write(buffer(i))
              state = 0
            case 2 =>
              dest2.write(buffer(i))
              state = 0
          }

          i += 1
        }
        dest1.flush()
        dest2.flush()
      }
    }
  }

}