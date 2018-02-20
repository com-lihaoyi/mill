package mill

import java.io._
import java.util

import ammonite.main.Cli
import mill.main.MainRunner
object Client{
  def WithLock[T](index: Int)(f: String => T): T = {
    val lockBase = "out/mill-worker-" + index
    new java.io.File(lockBase).mkdirs()
    val raf = new RandomAccessFile(lockBase+ "/lock", "rw")
    val channel = raf.getChannel
    channel.tryLock() match{
      case null =>
        raf.close()
        channel.close()
        if (index < 5) WithLock(index + 1)(f)
        else throw new Exception("Reached max process limit: " + 5)
      case locked =>
        try f(lockBase)
        finally{
          locked.release()
          raf.close()
          channel.close()
        }
    }
  }

  def main(args: Array[String]): Unit = {
    WithLock(1) { lockBase =>

      val start = System.currentTimeMillis()
      val inFile = new java.io.File(lockBase + "/stdin")
      val outErrFile = new java.io.File(lockBase + "/stdouterr")
      val metaFile = new java.io.File(lockBase + "/stdmeta")
      val runFile = new java.io.File(lockBase + "/run")
      val tmpRunFile = new java.io.File(lockBase + "/run-tmp")
      val pidFile = new java.io.File(lockBase + "/pid")

      outErrFile.delete()
      metaFile.delete()
      outErrFile.createNewFile()
      metaFile.createNewFile()

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

      if (!pidFile.exists()){
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
          .redirectOutput(outErrFile)
          .redirectError(outErrFile)
          .start()
      }

      val buffer = new Array[Byte](1024)
      val metaBuffer = new Array[Byte](1024)
      while({
        Thread.sleep(1)
        while({
          (if (outErr.available() > 0){
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
          }else false) |
          forward(buffer, System.in, in)
        })()

        runFile.exists()
      })()

      inFile.delete()
      outErrFile.delete()
      metaFile.delete()

    }
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
    import java.nio.file.{Paths, Files}
    val lockBase = Paths.get(args0(0))
    val runFile = lockBase.resolve("run")
    var lastRun = System.currentTimeMillis()
    val pidFile = lockBase.resolve("pid")
    var currentIn = System.in
    var currentOutErr: OutputStream = System.out
    var currentMeta: OutputStream = System.err
    val raf = new RandomAccessFile(lockBase + "/lock", "rw")
    val channel = raf.getChannel

    System.setOut(new PrintStream(new ProxyOutputStream(currentOutErr, currentMeta, 0), true))
    System.setErr(new PrintStream(new ProxyOutputStream(currentOutErr, currentMeta, 1), true))
    System.setIn(new ProxyInputStream(currentIn))
    Files.createFile(pidFile)
    var mainRunner = Option.empty[(Cli.Config, MainRunner)]
    try {
      while (System.currentTimeMillis() - lastRun < 60000) {
        if (!Files.exists(runFile)) Thread.sleep(10)
        else {
          currentIn = Files.newInputStream(lockBase.resolve("stdin"))
          currentOutErr = Files.newOutputStream(lockBase.resolve("stdouterr"))
          currentMeta = Files.newOutputStream(lockBase.resolve("stdmeta"))
          val argStream = Files.newInputStream(lockBase.resolve("run"))
          val interactive = argStream.read() != 0
          val argsLength = argStream.read()
          val args = Array.fill(argsLength){
            val n = argStream.read()
            val arr = new Array[Byte](n)
            argStream.read(arr)
            new String(arr)
          }
          try {
            val (_, mr) = mill.Main.main0(
              args,
              mainRunner,
              interactive,
              () => {
                channel.tryLock()  match{
                  case null =>
                    false
                  case lock =>
                    lock.release()
                    true
                }
              }
            )

            mainRunner = mr
          } catch{case MainRunner.WatchInterrupted(mr) =>
            mainRunner = Some((mr.config, mr))
          } finally{
            currentOutErr.flush()
            Files.delete(runFile)
            lastRun = System.currentTimeMillis()
          }
        }
      }
    }finally{
      Files.delete(pidFile)
    }
  }
}

