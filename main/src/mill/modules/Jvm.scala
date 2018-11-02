package mill.modules

import java.io._
import java.lang.reflect.Modifier
import java.net.URI
import java.nio.file.{FileSystems, Files, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections
import java.util.jar.{JarEntry, JarFile, JarOutputStream}

import ammonite.ops._
import coursier.{Cache, Dependency, Fetch, Repository, Resolution}
import coursier.util.{Gather, Task}
import geny.Generator
import mill.main.client.InputPumper
import mill.eval.{PathRef, Result}
import mill.util.{Ctx, IO}
import mill.util.Loose.Agg

import scala.collection.mutable
import scala.collection.JavaConverters._

object Jvm {

  def interactiveSubprocess(mainClass: String,
                            classPath: Agg[Path],
                            jvmArgs: Seq[String] = Seq.empty,
                            envArgs: Map[String, String] = Map.empty,
                            mainArgs: Seq[String] = Seq.empty,
                            workingDir: Path = null,
                            background: Boolean = false): Unit = {
    val args =
      Vector("java") ++
      jvmArgs ++
      Vector("-cp", classPath.mkString(File.pathSeparator), mainClass) ++
      mainArgs

    if (background) baseInteractiveSubprocess0(args, envArgs, workingDir)
    else baseInteractiveSubprocess(args, envArgs, workingDir)
  }

  def baseInteractiveSubprocess(commandArgs: Seq[String],
                                envArgs: Map[String, String],
                                workingDir: Path) = {
    val process = baseInteractiveSubprocess0(commandArgs, envArgs, workingDir)

    val exitCode = process.waitFor()
    if (exitCode == 0) ()
    else throw InteractiveShelloutException()
  }
  def baseInteractiveSubprocess0(commandArgs: Seq[String],
                                 envArgs: Map[String, String],
                                 workingDir: Path) = {
    val builder = new java.lang.ProcessBuilder()

    for ((k, v) <- envArgs){
      if (v != null) builder.environment().put(k, v)
      else builder.environment().remove(k)
    }
    builder.directory(workingDir.toIO)

    if (System.in.isInstanceOf[ByteArrayInputStream]){

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

  }


  def runLocal(mainClass: String,
               classPath: Agg[Path],
               mainArgs: Seq[String] = Seq.empty)
              (implicit ctx: Ctx): Unit = {
    inprocess(classPath, classLoaderOverrideSbtTesting = false, isolated = true, closeContextClassLoaderWhenDone = true, cl => {
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
                   isolated: Boolean,
                   closeContextClassLoaderWhenDone: Boolean,
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
    } else if (isolated) {
      mill.util.ClassLoader.create(urls.toVector, null)
    } else {
      mill.util.ClassLoader.create(urls.toVector, getClass.getClassLoader)
    }

    val oldCl = Thread.currentThread().getContextClassLoader
    Thread.currentThread().setContextClassLoader(cl)
    try {
      body(cl)
    } finally {
      if (closeContextClassLoaderWhenDone) {
        Thread.currentThread().setContextClassLoader(oldCl)
        cl.close()
      }
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

  /**
    * Create a jar file containing all files from the specified input Paths,
    * called out.jar in the implicit ctx.dest folder. An optional main class may
    * be provided for the jar. An optional filter function may also be provided to
    * selectively include/exclude specific files.
    * @param inputPaths - `Agg` of `Path`s containing files to be included in the jar
    * @param mainClass - optional main class for the jar
    * @param fileFilter - optional file filter to select files to be included.
    *                   Given a `Path` (from inputPaths) and a `RelPath` for the individual file,
    *                   return true if the file is to be included in the jar.
    * @param ctx - implicit `Ctx.Dest` used to determine the output directory for the jar.
    * @return - a `PathRef` for the created jar.
    */
  def createJar(inputPaths: Agg[Path],
                mainClass: Option[String] = None,
                fileFilter: (Path, RelPath) => Boolean = (p: Path, r: RelPath) => true)
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
        if !seen(mapping) && fileFilter(p, mapping)
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

  def newOutputStream(p: java.nio.file.Path, append: Boolean = false) = {
    val options =
      if(append) Seq(StandardOpenOption.APPEND)
      else Seq(StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)
    Files.newOutputStream(p, options:_*)
  }

  def createAssembly(inputPaths: Agg[Path],
                     mainClass: Option[String] = None,
                     prependShellScript: String = "",
                     base: Option[Path] = None,
                     assemblyRules: Seq[Assembly.Rule] = Assembly.defaultRules)
                    (implicit ctx: Ctx.Dest with Ctx.Log): PathRef = {

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

    Assembly.groupAssemblyEntries(inputPaths, assemblyRules).view
      .map {
        case (mapping, aggregate) =>
          zipFs.getPath(mapping) -> aggregate
      }
      .foreach {
        case (path, AppendEntry(entries)) =>
          val concatenated = new SequenceInputStream(
            Collections.enumeration(entries.map(_.inputStream).asJava))
          writeEntry(path, concatenated, append = Files.exists(path))
        case (path, WriteOnceEntry(entry)) =>
          if (Files.notExists(path)) {
            writeEntry(path, entry.inputStream, append = false)
          }
      }

    zipFs.close()
    val output = ctx.dest / "out.jar"

    // Prepend shell script and make it executable
    if (prependShellScript.isEmpty) mv(tmp, output)
    else{
      val lineSep = if (!prependShellScript.endsWith("\n")) "\n\r\n" else ""
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

  private def writeEntry(p: java.nio.file.Path, is: InputStream, append: Boolean): Unit = {
    if (p.getParent != null) Files.createDirectories(p.getParent)
    val outputStream = newOutputStream(p, append)

    IO.stream(is, outputStream)

    outputStream.close()
    is.close()
  }

  def universalScript(shellCommands: String,
                      cmdCommands: String,
                      shebang: Boolean = false): String = {
    Seq(
      if (shebang) "#!/usr/bin/env sh" else "",
      "@ 2>/dev/null # 2>nul & echo off & goto BOF\r",
      ":",
      shellCommands.replaceAll("\r\n|\n", "\n"),
      "exit",
      Seq(
        "",
        ":BOF",
        "@echo off",
        cmdCommands.replaceAll("\r\n|\n", "\r\n"),
        "exit /B %errorlevel%",
        ""
      ).mkString("\r\n")
    ).filterNot(_.isEmpty).mkString("\n")
  }

  def launcherUniversalScript(mainClass: String,
                              shellClassPath: Agg[String],
                              cmdClassPath: Agg[String],
                              jvmArgs: Seq[String],
                              shebang: Boolean = false) = {
    universalScript(
      shellCommands =
        s"""exec java ${jvmArgs.mkString(" ")} $$JAVA_OPTS -cp "${shellClassPath.mkString(":")}" $mainClass "$$@"""",
      cmdCommands =
        s"""java ${jvmArgs.mkString(" ")} %JAVA_OPTS% -cp "${cmdClassPath.mkString(";")}" $mainClass %*""",
      shebang = shebang
    )
  }
  def createLauncher(mainClass: String,
                     classPath: Agg[Path],
                     jvmArgs: Seq[String])
                    (implicit ctx: Ctx.Dest)= {
    val isWin = scala.util.Properties.isWin
    val isBatch = isWin &&
      !(org.jline.utils.OSUtils.IS_CYGWIN
        || org.jline.utils.OSUtils.IS_MINGW
        || "MSYS" == System.getProperty("MSYSTEM"))
    val outputPath = ctx.dest / (if (isBatch) "run.bat" else "run")
    val classPathStrs = classPath.map(_.toString)

    write(outputPath, launcherUniversalScript(mainClass, classPathStrs, classPathStrs, jvmArgs))

    if (!isWin) {
      val perms = Files.getPosixFilePermissions(outputPath.toNIO)
      perms.add(PosixFilePermission.GROUP_EXECUTE)
      perms.add(PosixFilePermission.OWNER_EXECUTE)
      perms.add(PosixFilePermission.OTHERS_EXECUTE)
      Files.setPosixFilePermissions(outputPath.toNIO, perms)
    }
    PathRef(outputPath)
  }

  /**
    * Resolve dependencies using Coursier.
    *
    * We do not bother breaking this out into the separate ZincWorkerApi classpath,
    * because Coursier is already bundled with mill/Ammonite to support the
    * `import $ivy` syntax.
    */
  def resolveDependencies(repositories: Seq[Repository],
                          deps: TraversableOnce[coursier.Dependency],
                          force: TraversableOnce[coursier.Dependency],
                          sources: Boolean = false,
                          mapDependencies: Option[Dependency => Dependency] = None): Result[Agg[PathRef]] = {

    val (_, resolution) = resolveDependenciesMetadata(
      repositories, deps, force, mapDependencies
    )
    val errs = resolution.metadataErrors
    if(errs.nonEmpty) {
      val header =
        s"""|
            |Resolution failed for ${errs.length} modules:
            |--------------------------------------------
            |""".stripMargin

      val errLines = errs.map {
        case ((module, vsn), errMsgs) => s"  ${module.trim}:$vsn \n\t" + errMsgs.mkString("\n\t")
      }.mkString("\n")
      val msg = header + errLines + "\n"
      Result.Failure(msg)
    } else {

      def load(artifacts: Seq[coursier.Artifact]) = {
        val logger = None

        import scala.concurrent.ExecutionContext.Implicits.global
        val loadedArtifacts = Gather[Task].gather(
          for (a <- artifacts)
            yield coursier.Cache.file[Task](a, logger = logger).run
              .map(a.isOptional -> _)
        ).unsafeRun

        val errors = loadedArtifacts.collect {
          case (false, Left(x)) => x
          case (true, Left(x)) if !x.notFound => x
        }
        val successes = loadedArtifacts.collect { case (_, Right(x)) => x }
        (errors, successes)
      }

      val sourceOrJar =
        if (sources) resolution.classifiersArtifacts(Seq("sources"))
        else resolution.artifacts(true)
      val (errors, successes) = load(sourceOrJar)
      if(errors.isEmpty){
        mill.Agg.from(
          successes.map(p => PathRef(Path(p), quick = true)).filter(_.path.ext == "jar")
        )
      }else{
        val errorDetails = errors.map(e => s"${ammonite.util.Util.newLine}  ${e.describe}").mkString
        Result.Failure("Failed to load source dependencies" + errorDetails)
      }
    }
  }


  def resolveDependenciesMetadata(repositories: Seq[Repository],
                                  deps: TraversableOnce[coursier.Dependency],
                                  force: TraversableOnce[coursier.Dependency],
                                  mapDependencies: Option[Dependency => Dependency] = None) = {

    val forceVersions = force
      .map(mapDependencies.getOrElse(identity[Dependency](_)))
      .map{d => d.module -> d.version}
      .toMap

    val start = Resolution(
      deps.map(mapDependencies.getOrElse(identity[Dependency](_))).toSet,
      forceVersions = forceVersions,
      mapDependencies = mapDependencies
    )

    val fetch = Fetch.from(repositories, Cache.fetch[Task]())

    import scala.concurrent.ExecutionContext.Implicits.global
    val resolution = start.process.run(fetch).unsafeRun()
    (deps.toSeq, resolution)
  }
}
