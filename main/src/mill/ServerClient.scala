package mill

import java.io._
import java.nio.channels.FileChannel
import java.util

import ammonite.main.Cli
import mill.eval.Evaluator
import mill.main.MainRunner

class ServerClient(lockBase: String){
  val inFile = new java.io.File(lockBase + "/stdin")
  val outErrFile = new java.io.File(lockBase + "/stdouterr")
  val metaFile = new java.io.File(lockBase + "/stdmeta")
  val logFile = new java.io.File(lockBase + "/log")
  val runFile = new java.io.File(lockBase + "/run")
  val tmpRunFile = new java.io.File(lockBase + "/run-tmp")
  val pidFile = new java.io.File(lockBase + "/pid")
}
object Client{
  def WithLock[T](index: Int)(f: String => T): T = {
    val lockBase = "out/mill-worker-" + index
    new java.io.File(lockBase).mkdirs()
    val lockFile = new RandomAccessFile(lockBase+ "/lock", "rw")
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

  def main(args: Array[String]): Unit = {
    WithLock(1) { lockBase =>
      new Client(lockBase).run(args)
    }
  }
}


class Client(lockBase: String) extends ServerClient(lockBase){
  def run(args: Array[String]) = {

    outErrFile.delete()
    metaFile.delete()
    outErrFile.createNewFile()
    metaFile.createNewFile()
    inFile.createNewFile()
    inFile.createNewFile()
    logFile.createNewFile()

    val f = new FileOutputStream(tmpRunFile)
    f.write(if (System.console() != null) 1 else 0)
    f.write(args.length)
    var i = 0
    while (i < args.length){
      f.write(args(i).length)
      f.write(args(i).getBytes)
      i += 1
    }
    f.flush()

    tmpRunFile.renameTo(runFile)
    val in = new FileOutputStream(inFile)
    val outErr = new FileInputStream(outErrFile)
    val meta = new FileInputStream(metaFile)

    val pidRaf = new RandomAccessFile(pidFile, "rw")
    val pidLockChannel = pidRaf.getChannel

    if (!probeLock(pidLockChannel)){
      val selfJar = getClass.getProtectionDomain.getCodeSource.getLocation.toURI.getPath

      val l = new java.util.ArrayList[String]
      l.add("java")
      val props = System.getProperties
      val keys = props.stringPropertyNames().iterator()
      while(keys.hasNext){
        val k = keys.next()
        if (k.startsWith("MILL_")) l.add("-D" + k + "=" + props.getProperty(k))
      }
      l.add("-cp")
      l.add(selfJar)
      l.add("mill.Server")
      l.add(lockBase.toString)

      new java.lang.ProcessBuilder()
        .command(l)
        .redirectInput(inFile)
        .redirectOutput(logFile)
        .redirectError(logFile)
        .start()
    }


    while(!probeLock(pidLockChannel)) Thread.sleep(3)

    try {
      val buffer = new Array[Byte](1024)
      val metaBuffer = new Array[Byte](1024)
      while ({
        Thread.sleep(3)
        while ( {
          forwardForked(buffer, metaBuffer, meta, outErr) |
          forward(buffer, System.in, in)
        }) ()

        runFile.exists() && probeLock(pidLockChannel)
      }) ()
    }finally {
      pidLockChannel.close()

//      pidFile.delete()
      inFile.delete()
      outErrFile.delete()
      metaFile.delete()
    }
  }

  def probeLock(pidLockChannel: FileChannel) = {

    pidLockChannel.tryLock() match{
      case null => true
      case locked =>
        locked.release()
        false
    }

  }
  def forwardForked(buffer: Array[Byte],
                    metaBuffer: Array[Byte],
                    meta: InputStream,
                    outErr: InputStream) = {

    if (outErr.available() > 0){
      val outErrN = outErr.read(buffer)
      if (outErrN > 0) {
        var metaN = 0
        while (metaN < outErrN) {
          val delta = meta.read(metaBuffer, 0, outErrN - metaN)
          if (delta > 0) {
            var i = 0
            while (i < delta) {
              metaBuffer(i) match {
                case 0 => System.out.write(buffer(metaN + i))
                case 1 => System.err.write(buffer(metaN + i))
              }
              i += 1
            }
            metaN += delta
          }
        }
      }

      true
    }else false
  }
  def forward(buffer: Array[Byte], src: InputStream, dest: OutputStream) = {
    if (src.available() != 0){
      val n = src.read(buffer)
      dest.write(buffer, 0, n)
      true
    }else false
  }
}

class ProxyOutputStream(x: => java.io.OutputStream,
                        meta: => java.io.OutputStream,
                        key: Int) extends java.io.OutputStream  {
  override def write(b: Int) = Server.synchronized{
    x.write(b)
    meta.write(key)
  }
}
class ProxyInputStream(x: => java.io.InputStream) extends java.io.InputStream{
  def read() = x.read()
  override def read(b: Array[Byte], off: Int, len: Int) = x.read(b, off, len)
  override def read(b: Array[Byte]) = x.read(b)
}

object Server{
  def main(args0: Array[String]): Unit = {
    new Server(args0(0)).run()

  }
}

class ProbeThread(lockChannel: FileChannel,
                  mainThread: Thread,
                  log: PrintStream) extends Runnable{
  var running = true
  def run() = {
    while({
      Thread.sleep(3)
      lockChannel.tryLock() match{
        case null => true && running
        case locked =>
          locked.release()
          lockChannel.close()
          System.exit(0)
          false
      }
    })()
  }
}

class Server(lockBase: String) extends ServerClient(lockBase){
  var lastRun = System.currentTimeMillis()
  var currentIn = System.in
  var currentOutErr: OutputStream = System.out
  var currentMeta: OutputStream = System.err
  val lockFile = new RandomAccessFile(lockBase + "/lock", "rw")
  val channel = lockFile.getChannel
  var stateCache = Option.empty[Evaluator.State]

  def run() = {
    val originalStdout = System.out
    originalStdout.println("Initializing")
    val pidRaf = new RandomAccessFile(pidFile, "rw")
    val lockChannel = pidRaf.getChannel
    val lock = lockChannel.tryLock()
    if (lock == null) throw new Exception("PID already present")
    originalStdout.println("Locked pid file")
    try {
      while (System.currentTimeMillis() - lastRun < 60000) {
        pollOrRun(originalStdout)
        originalStdout.println("Delta " + (System.currentTimeMillis() - lastRun))
        originalStdout.println("Threads " + Thread.activeCount())
      }
    }finally{
      originalStdout.println("Exiting Server... " + System.currentTimeMillis())
      lock.release()
      lockChannel.close()
      pidRaf.close()
      pidFile.delete()
    }
    originalStdout.println("END")
  }
  def pollOrRun(originalStdout: PrintStream) = {
    if (!runFile.exists()) Thread.sleep(30)
    else try{
      originalStdout.println("Handling Run")
      handleRun(originalStdout)
    }catch{
      case e: Throwable =>
        originalStdout.println("Run Failed")
        e.printStackTrace(originalStdout)
    }finally{
      lastRun = System.currentTimeMillis()
      originalStdout.println("Updating lastRun " + lastRun)
    }
  }
  def handleRun(originalStdout: PrintStream) = {
    currentIn = new FileInputStream(inFile)
    currentOutErr = new FileOutputStream(outErrFile)
    currentMeta = new FileOutputStream(metaFile)
    val lockChannel = lockFile.getChannel
    val probe = new ProbeThread(lockChannel, Thread.currentThread(), originalStdout)
    val probeThread = new Thread(probe)
    probeThread.start()
    val argStream = new FileInputStream(runFile)
    val interactive = argStream.read() != 0
    val argsLength = argStream.read()
    val args = Array.fill(argsLength){
      val n = argStream.read()
      val arr = new Array[Byte](n)
      argStream.read(arr)
      new String(arr)
    }
    originalStdout.println("Parsed Args " + args.toList)
    try {
      originalStdout.println("Running Main")
      val (_, newStateCache) = mill.Main.main0(
        args,
        stateCache,
        interactive,
        () => {
          channel.tryLock()  match{
            case null =>
              false
            case lock =>
              lock.release()
              true
          }
        },
        new ProxyInputStream(currentIn),
        new PrintStream(new ProxyOutputStream(currentOutErr, currentMeta, 0), true),
        new PrintStream(new ProxyOutputStream(currentOutErr, currentMeta, 1), true)
      )
      originalStdout.println("Finished Main")
      stateCache = newStateCache
    } catch{case MainRunner.WatchInterrupted(sc) =>
      stateCache = sc
    } finally{
//      lockChannel.close()
//      lockFile.close()
      probe.running = false
      probeThread.join()
      runFile.delete()

    }
  }
}