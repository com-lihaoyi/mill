package mill.modules

import java.io.{ByteArrayInputStream, FileOutputStream, File}
import java.lang.reflect.Modifier
import java.net.{URI, URLClassLoader}
import java.nio.file.{FileSystems, Files, OpenOption, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermission
import java.util.jar.{JarEntry, JarFile, JarOutputStream}

import ammonite.ops._
import geny.Generator
import mill.clientserver.InputPumper
import mill.eval.PathRef
import mill.util.{Ctx, IO}
import mill.util.Loose.Agg

import scala.collection.mutable


object Jvm {

  def interactiveSubprocess(mainClass: String,
                            classPath: Agg[Path],
                            jvmArgs: Seq[String] = Seq.empty,
                            envArgs: Map[String, String] = Map.empty,
                            mainArgs: Seq[String] = Seq.empty,
                            workingDir: Path = null): Unit = {
    baseInteractiveSubprocess(
      Vector("java") ++
        jvmArgs ++
        Vector("-cp", classPath.mkString(File.pathSeparator), mainClass) ++
        mainArgs,
      envArgs,
      workingDir
    )
  }

  def baseInteractiveSubprocess(commandArgs: Seq[String],
                                envArgs: Map[String, String],
                                workingDir: Path) = {
    val builder = new java.lang.ProcessBuilder()

    for ((k, v) <- envArgs){
      if (v != null) builder.environment().put(k, v)
      else builder.environment().remove(k)
    }
    builder.directory(workingDir.toIO)

    val process = if (System.in.isInstanceOf[ByteArrayInputStream]){

      val process = builder
        .command(commandArgs:_*)
        .start()

      val sources = Seq(
        process.getInputStream -> System.out,
        process.getErrorStream -> System.err,
        System.in -> process.getOutputStream
      )

      for((std, dest) <- sources){
        new Thread(new InputPumper(std, dest, false)).start()
      }
      process
    }else{
      builder
        .command(commandArgs:_*)
        .inheritIO()
        .start()
    }

    val exitCode = process.waitFor()
    if (exitCode == 0) ()
    else throw InteractiveShelloutException()
  }

  def runLocal(mainClass: String,
               classPath: Agg[Path],
               mainArgs: Seq[String] = Seq.empty)
              (implicit ctx: Ctx): Unit = {
    inprocess(classPath, classLoaderOverrideSbtTesting = false, cl => {
      getMainMethod(mainClass, cl).invoke(null, mainArgs.toArray)
    })
  }

  private def getMainMethod(mainClassName: String, cl: ClassLoader) = {
    val mainClass = cl.loadClass(mainClassName)
    val method = mainClass.getMethod("main", classOf[Array[String]])
    // jvm allows the actual main class to be non-public and to run a method in the non-public class,
    //  we need to make it accessible
    method.setAccessible(true)
    val modifiers = method.getModifiers
    if (!Modifier.isPublic(modifiers))
      throw new NoSuchMethodException(mainClassName + ".main is not public")
    if (!Modifier.isStatic(modifiers))
      throw new NoSuchMethodException(mainClassName + ".main is not static")
    method
  }



  def inprocess[T](classPath: Agg[Path],
                   classLoaderOverrideSbtTesting: Boolean,
                   body: ClassLoader => T)
                  (implicit ctx: Ctx.Home): T = {
    val urls = classPath.map(_.toIO.toURI.toURL)
    val cl = if (classLoaderOverrideSbtTesting) {
      val outerClassLoader = getClass.getClassLoader
      mill.util.ClassLoader.create(urls.toVector, null, customFindClass = { name =>
        if (name.startsWith("sbt.testing."))
          Some(outerClassLoader.loadClass(name))
        else None
      })
    } else {
      mill.util.ClassLoader.create(urls.toVector, null)
    }
    val oldCl = Thread.currentThread().getContextClassLoader
    Thread.currentThread().setContextClassLoader(cl)
    try {
      body(cl)
    }finally{
      Thread.currentThread().setContextClassLoader(oldCl)
      cl.close()
    }
  }

  def subprocess(mainClass: String,
                 classPath: Agg[Path],
                 jvmArgs: Seq[String] = Seq.empty,
                 envArgs: Map[String, String] = Map.empty,
                 mainArgs: Seq[String] = Seq.empty,
                 workingDir: Path = null)
                (implicit ctx: Ctx) = {

    val commandArgs =
      Vector("java") ++
      jvmArgs ++
      Vector("-cp", classPath.mkString(File.pathSeparator), mainClass) ++
      mainArgs

    val workingDir1 = Option(workingDir).getOrElse(ctx.dest)
    mkdir(workingDir1)
    val builder =
      new java.lang.ProcessBuilder()
        .directory(workingDir1.toIO)
        .command(commandArgs:_*)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)

    for((k, v) <- envArgs) builder.environment().put(k, v)
    val proc = builder.start()
    val stdout = proc.getInputStream
    val stderr = proc.getErrorStream
    val sources = Seq(
      (stdout, Left(_: Bytes), ctx.log.outputStream),
      (stderr, Right(_: Bytes),ctx.log.errorStream )
    )
    val chunks = mutable.Buffer.empty[Either[Bytes, Bytes]]
    while(
    // Process.isAlive doesn't exist on JDK 7 =/
      util.Try(proc.exitValue).isFailure ||
        stdout.available() > 0 ||
        stderr.available() > 0
    ){
      var readSomething = false
      for ((subStream, wrapper, parentStream) <- sources){
        while (subStream.available() > 0){
          readSomething = true
          val array = new Array[Byte](subStream.available())
          val actuallyRead = subStream.read(array)
          chunks.append(wrapper(new ammonite.ops.Bytes(array)))
          parentStream.write(array, 0, actuallyRead)
        }
      }
      // if we did not read anything sleep briefly to avoid spinning
      if(!readSomething)
        Thread.sleep(2)
    }

    if (proc.exitValue() != 0) throw new InteractiveShelloutException()
    else ammonite.ops.CommandResult(proc.exitValue(), chunks)
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

  def createJar(inputPaths: Agg[Path], mainClass: Option[String] = None)
               (implicit ctx: Ctx.Dest): PathRef = {
    val outputPath = ctx.dest / "out.jar"
    rm(outputPath)

    val seen = mutable.Set.empty[RelPath]
    seen.add("META-INF" / "MANIFEST.MF")
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
        if !seen(mapping)
      } {
        seen.add(mapping)
        val entry = new JarEntry(mapping.toString)
        entry.setTime(file.mtime.toMillis)
        jar.putNextEntry(entry)
        jar.write(read.bytes(file))
        jar.closeEntry()
      }
    } finally {
      jar.close()
    }

    PathRef(outputPath)
  }

  def newOutputStream(p: java.nio.file.Path) = Files.newOutputStream(
    p,
    StandardOpenOption.TRUNCATE_EXISTING,
    StandardOpenOption.CREATE
  )

  def createAssembly(inputPaths: Agg[Path],
                     mainClass: Option[String] = None,
                     prependShellScript: String = "",
                     base: Option[Path] = None,
                     isWin: Boolean = scala.util.Properties.isWin)
                    (implicit ctx: Ctx.Dest) = {
    val tmp = ctx.dest / "out-tmp.jar"

    val baseUri = "jar:" + tmp.toIO.getCanonicalFile.toURI.toASCIIString
    val hm = new java.util.HashMap[String, String]()

    base match{
      case Some(b) => cp(b, tmp)
      case None => hm.put("create", "true")
    }

    val zipFs = FileSystems.newFileSystem(URI.create(baseUri), hm)

    val manifest = createManifest(mainClass)
    val manifestPath = zipFs.getPath(JarFile.MANIFEST_NAME)
    Files.createDirectories(manifestPath.getParent)
    val manifestOut = newOutputStream(manifestPath)
    manifest.write(manifestOut)
    manifestOut.close()

    def isSignatureFile(mapping: String): Boolean =
      Set("sf", "rsa", "dsa").exists(ext => mapping.toLowerCase.endsWith(s".$ext"))

    for(v <- classpathIterator(inputPaths)){
      val (file, mapping) = v
      val p = zipFs.getPath(mapping)
      if (p.getParent != null) Files.createDirectories(p.getParent)
      if (!isSignatureFile(mapping)) {
        val outputStream = newOutputStream(p)
        IO.stream(file, outputStream)
        outputStream.close()
      }
      file.close()
    }
    zipFs.close()
    val output = ctx.dest / "out.jar"

    // Prepend shell script and make it executable
    if (prependShellScript.isEmpty) mv(tmp, output)
    else{
      val lineSep = if (isWin) "\r\n" else "\n"
      val outputStream = newOutputStream(output.toNIO)
      IO.stream(new ByteArrayInputStream((prependShellScript + lineSep).getBytes()), outputStream)
      IO.stream(read.getInputStream(tmp), outputStream)
      outputStream.close()

      if (!scala.util.Properties.isWin) {
        val perms = Files.getPosixFilePermissions(output.toNIO)
        perms.add(PosixFilePermission.GROUP_EXECUTE)
        perms.add(PosixFilePermission.OWNER_EXECUTE)
        perms.add(PosixFilePermission.OTHERS_EXECUTE)
        Files.setPosixFilePermissions(output.toNIO, perms)
      }
    }

    PathRef(output)
  }


  def classpathIterator(inputPaths: Agg[Path]) = {
    Generator.from(inputPaths)
      .filter(exists)
      .flatMap{
        p =>
          if (p.isFile) {
            val jf = new JarFile(p.toIO)
            import collection.JavaConverters._
            Generator.selfClosing((
              for(entry <- jf.entries().asScala if !entry.isDirectory)
                yield (jf.getInputStream(entry), entry.getName),
              () => jf.close()
            ))
          }
          else {
            ls.rec.iter(p)
              .filter(_.isFile)
              .map(sub => read.getInputStream(sub) -> sub.relativeTo(p).toString)
          }
      }

  }

  def launcherShellScript(isWin: Boolean,
                          mainClass: String,
                          classPath: Agg[String],
                          jvmArgs: Seq[String]) = {
    val cp = classPath.mkString(File.pathSeparator)
    if (isWin)
      s"""@echo off
         |
         |java ${jvmArgs.mkString(" ")} %JAVA_OPTS% -cp "$cp" $mainClass %*
       """.stripMargin.split('\n').mkString("\r\n")
    else
      s"""#!/usr/bin/env sh
         |
         |exec java ${jvmArgs.mkString(" ")} $$JAVA_OPTS -cp "$cp" $mainClass "$$@"
       """.stripMargin
  }
  def createLauncher(mainClass: String,
                     classPath: Agg[Path],
                     jvmArgs: Seq[String])
                    (implicit ctx: Ctx.Dest)= {
    val isWin = scala.util.Properties.isWin
    val outputPath = ctx.dest / (if (isWin) "run.bat" else "run")

    write(outputPath, launcherShellScript(isWin, mainClass, classPath.map(_.toString), jvmArgs))

    if (!isWin) {
      val perms = Files.getPosixFilePermissions(outputPath.toNIO)
      perms.add(PosixFilePermission.GROUP_EXECUTE)
      perms.add(PosixFilePermission.OWNER_EXECUTE)
      perms.add(PosixFilePermission.OTHERS_EXECUTE)
      Files.setPosixFilePermissions(outputPath.toNIO, perms)
    }
    PathRef(outputPath)
  }

}
