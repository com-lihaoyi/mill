package mill

import java.io.{InputStream, OutputStream, PrintStream, RandomAccessFile}
import java.nio.file.{Files, Path, Paths}
import java.util
object Client{
  def WithLock[T](index: Int)(f: Path => T): T = {
    val lockFile = Paths.get("out/mill-worker-" + index + "/lock")
    Files.createDirectories(lockFile.getParent)
    val raf = new RandomAccessFile(lockFile.toFile, "rw")
    val channel = raf.getChannel
    channel.tryLock() match{
      case null =>
        raf.close()
        channel.close()
        if (index < 5) WithLock(index + 1)(f)
        else throw new Exception("Reached max process limit: " + 5)
      case locked =>
        try f(lockFile.getParent)
        finally{
          locked.release()
          raf.close()
          channel.close()
        }
    }
  }

  def main(args: Array[String]): Unit = {
    WithLock(1) { lockBase =>
      val inFile = lockBase.resolve("stdin")
      val outFile = lockBase.resolve("stdout")
      val errFile = lockBase.resolve("stderr")
      val runFile = lockBase.resolve("run")
      val tmpRunFile = lockBase.resolve("run-tmp")

      Files.createFile(outFile)
      Files.createFile(errFile)

      Files.write(tmpRunFile, java.util.Arrays.asList(args:_*))
      Files.move(tmpRunFile, runFile)
      val start = System.currentTimeMillis()
      val in = Files.newOutputStream(inFile)
      val out = Files.newInputStream(outFile)
      val err = Files.newInputStream(errFile)
      if (!Files.exists(lockBase.resolve("pid"))){
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
          .redirectInput(inFile.toFile)
          .redirectOutput(outFile.toFile)
          .redirectError(errFile.toFile)
          .start()
      }


      val buffer = new Array[Byte](1024)
      while({
        Thread.sleep(1)
        forward(buffer, out, System.out) |
        forward(buffer, err, System.err) |
        forward(buffer, System.in, in) |
        Files.exists(runFile)
      })()
      println("DELTA: " + (System.currentTimeMillis() - start))

      Files.delete(inFile)
      Files.delete(outFile)
      Files.delete(errFile)

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


object Server{
  def main(args: Array[String]): Unit = {
    val lockBase = Paths.get(args(0))
    val runFile = lockBase.resolve("run")
    var lastRun = System.currentTimeMillis()
    val pidFile = lockBase.resolve("pid")
    Files.createFile(pidFile)
    try {
      while (System.currentTimeMillis() - lastRun < 60000) {
        if (!Files.exists(runFile)) Thread.sleep(10)
        else {
          val in = Files.newInputStream(lockBase.resolve("stdin"))
          val out = Files.newOutputStream(lockBase.resolve("stdout"))
          val err = Files.newOutputStream(lockBase.resolve("stderr"))
          val args = new String(Files.readAllBytes(runFile)).split('\n')
          try {
            System.setOut(new PrintStream(out))
            System.setErr(new PrintStream(err))
            System.setIn(in)
            mill.Main.main0(args)
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

