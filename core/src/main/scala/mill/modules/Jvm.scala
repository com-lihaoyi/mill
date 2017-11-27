package mill.modules

import java.io.FileOutputStream
import java.net.URLClassLoader
import java.nio.file.attribute.PosixFilePermission
import java.util.jar.{JarEntry, JarFile, JarOutputStream}

import ammonite.ops._
import mill.define.Task
import mill.eval.PathRef
import mill.util.Ctx

import scala.annotation.tailrec
import scala.collection.mutable


object Jvm {
  def gatherClassloaderJars(): Seq[Path] = {
    val allJars = collection.mutable.Buffer.empty[Path]
    var currentClassloader = Thread.currentThread().getContextClassLoader
    while(currentClassloader != null){
      currentClassloader match{
        case u: URLClassLoader => allJars.appendAll(u.getURLs.map(x => Path(x.getFile)))
        case _ =>
      }
      currentClassloader = currentClassloader.getParent
    }
    allJars
  }

  def interactiveSubprocess(mainClass: String,
                            classPath: Seq[Path],
                            options: Seq[String] = Seq.empty): Unit = {
    import ammonite.ops.ImplicitWd._
    %("java", "-cp", classPath.mkString(":"), mainClass, options)
  }

  def subprocess(mainClass: String,
                 classPath: Seq[Path],
                 jvmOptions: Seq[String] = Seq.empty,
                 options: Seq[String] = Seq.empty,
                 workingDir: Path = ammonite.ops.pwd)
                (implicit ctx: Ctx) = {

    val commandArgs =
      Vector("java") ++
      jvmOptions ++
      Vector("-cp", classPath.mkString(":"), mainClass) ++
      options

    val proc =
      new java.lang.ProcessBuilder()
        .directory(workingDir.toIO)
        .command(commandArgs:_*)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()

    val stdout = proc.getInputStream
    val stderr = proc.getErrorStream
    val sources = Seq(stdout , stderr)
    while(
    // Process.isAlive doesn't exist on JDK 7 =/
      util.Try(proc.exitValue).isFailure ||
        stdout.available() > 0 ||
        stderr.available() > 0
    ){
      var readSomething = false
      for (std <- sources){
        while (std.available() > 0){
          readSomething = true
          val array = new Array[Byte](std.available())
          val actuallyRead = std.read(array)
          ctx.log.outputStream.write(array, 0, actuallyRead)
        }
      }
      // if we did not read anything sleep briefly to avoid spinning
      if(!readSomething)
        Thread.sleep(2)
    }

    if (proc.exitValue() != 0) throw new InteractiveShelloutException()
  }

  private def createManifest(mainClass: Option[String]) = {
    val m = new java.util.jar.Manifest()
    m.getMainAttributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
    m.getMainAttributes.putValue( "Created-By", "Scala mill" )
    mainClass.foreach(
      m.getMainAttributes.put(java.util.jar.Attributes.Name.MAIN_CLASS, _)
    )
    m
  }

  // TODO: outputPath default to ctx.dest
  def createJar(outputPath: Path, inputPaths: Seq[Path], mainClass: Option[String] = None)
               (implicit ctx: Ctx.DestCtx): PathRef = {
    rm(outputPath)
    if(inputPaths.nonEmpty) {
      mkdir(outputPath/up)

      val jar = new JarOutputStream(
        new FileOutputStream(outputPath.toIO),
        createManifest(mainClass)
      )

      try{
        assert(inputPaths.forall(exists(_)))
        for{
          p <- inputPaths
          (file, mapping) <-
            if (p.isFile) Iterator(p -> empty/p.last)
            else ls.rec(p).filter(_.isFile).map(sub => sub -> sub.relativeTo(p))
        } {
          val entry = new JarEntry(mapping.toString)
          entry.setTime(file.mtime.toMillis)
          jar.putNextEntry(entry)
          jar.write(read.bytes(file))
          jar.closeEntry()
        }
      } finally {
        jar.close()
      }

    }
    PathRef(outputPath)
  }


  // TODO: outputPath default to ctx.dest
  def createAssembly(outputPath: Path,
                      inputPaths: Seq[Path],
                     mainClass: Option[String] = None,
                     prependShellScript: String = "")
                    (implicit ctx: Ctx.DestCtx): PathRef = {
    rm(outputPath)

    if(inputPaths.nonEmpty) {
      mkdir(outputPath/up)

      val output = new FileOutputStream(outputPath.toIO)

      // Prepend shell script and make it executable
      if (prependShellScript.nonEmpty) {
        output.write((prependShellScript + "\n").getBytes)
        val perms = java.nio.file.Files.getPosixFilePermissions(outputPath.toNIO)
        perms.add(PosixFilePermission.GROUP_EXECUTE)
        perms.add(PosixFilePermission.OWNER_EXECUTE)
        perms.add(PosixFilePermission.OTHERS_EXECUTE)
        java.nio.file.Files.setPosixFilePermissions(outputPath.toNIO, perms)
      }

      val jar = new JarOutputStream(
        output,
        createManifest(mainClass)
      )

      val seen = mutable.Set("META-INF/MANIFEST.MF")
      try{
        assert(inputPaths.forall(exists(_)))

        for{
          p <- inputPaths

          (file, mapping) <-
            if (p.isFile) {
              val jf = new JarFile(p.toIO)
              import collection.JavaConverters._
              for(entry <- jf.entries().asScala if !entry.isDirectory) yield {
                read.bytes(jf.getInputStream(entry)) -> entry.getName
              }
            }
            else {
              ls.rec(p).iterator
                .filter(_.isFile)
                .map(sub => read.bytes(sub) -> sub.relativeTo(p).toString)
            }
          if !seen(mapping)
        } {
          seen.add(mapping)
          val entry = new JarEntry(mapping.toString)
          jar.putNextEntry(entry)
          jar.write(file)
          jar.closeEntry()
        }
      } finally {
        jar.close()
        output.close()
      }

    }
    PathRef(outputPath)
  }

}
