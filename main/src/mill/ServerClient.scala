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
      val outFile = new java.io.File(lockBase + "/stdout")
      val errFile = new java.io.File(lockBase + "/stderr")
      val runFile = new java.io.File(lockBase + "/run")
      val tmpRunFile = new java.io.File(lockBase + "/run-tmp")
      val pidFile = new java.io.File(lockBase + "/pid")

      outFile.createNewFile()
      errFile.createNewFile()

      val f = new FileOutputStream(tmpRunFile)
      var i = 0
      while (i < args.length){
        f.write(args(i).getBytes)
        f.write('\n')
        i += 1
      }
      tmpRunFile.renameTo(runFile)

      val in = new FileOutputStream(inFile)
      val out = new FileInputStream(outFile)
      val err = new FileInputStream(errFile)
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
          .redirectOutput(outFile)
          .redirectError(errFile)
          .start()
      }
      val buffer = new Array[Byte](1024)
      while({
        Thread.sleep(1)
        while({
          forward(buffer, out, System.out) |
          forward(buffer, err, System.err) |
          forward(buffer, System.in, in)
        })()

        runFile.exists()
      })()

      inFile.delete()
      outFile.delete()
      errFile.delete()

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

class ProxyOutputStream(x: => java.io.OutputStream) extends java.io.OutputStream  {
  def write(b: Int) = x.write(b)
  override def write(b: Array[Byte], off: Int, len: Int) = x.write(b, off, len)
  override def write(b: Array[Byte]) = x.write(b)
}
class ProxyInputStream(x: => java.io.InputStream) extends java.io.InputStream{
  def read() = x.read()
  override def read(b: Array[Byte], off: Int, len: Int) = x.read(b, off, len)
  override def read(b: Array[Byte]) = x.read(b)
}
object Server{
  def main(args: Array[String]): Unit = {
    import java.nio.file.{Paths, Files}
    val lockBase = Paths.get(args(0))
    val runFile = lockBase.resolve("run")
    var lastRun = System.currentTimeMillis()
    val pidFile = lockBase.resolve("pid")
    var currentIn = System.in
    var currentOut: OutputStream = System.out
    var currentErr: OutputStream = System.err
    System.setOut(new PrintStream(new ProxyOutputStream(currentOut), true))
    System.setErr(new PrintStream(new ProxyOutputStream(currentErr), true))
    System.setIn(new ProxyInputStream(currentIn))
    Files.createFile(pidFile)
    var mainRunner = Option.empty[(Cli.Config, MainRunner)]
    try {
      while (System.currentTimeMillis() - lastRun < 60000) {
        if (!Files.exists(runFile)) Thread.sleep(10)
        else {
          val start = System.currentTimeMillis()
          currentIn = Files.newInputStream(lockBase.resolve("stdin"))
          currentOut = Files.newOutputStream(lockBase.resolve("stdout"))
          currentErr = Files.newOutputStream(lockBase.resolve("stderr"))
          val args = new String(Files.readAllBytes(runFile)).split('\n')
          try {

            val (_, mr) = mill.Main.main0(args, mainRunner)
            val end = System.currentTimeMillis()

            mainRunner = mr
            System.out.flush()
            System.err.flush()
            pprint.log(end - start)
          } finally {
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

