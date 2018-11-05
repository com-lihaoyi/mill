package mill.modules

import java.io._
import java.lang.reflect.Modifier
import java.net.URI
import java.nio.file.{FileSystems, Files, StandardOpenOption}
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections
import java.util.jar.{JarEntry, JarFile, JarOutputStream}

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
  /**
    * Runs a JVM subprocess with the given configuration and returns a
    * [[os.CommandResult]] with it's aggregated output and error streams
    */
  def callSubprocess(mainClass: String,
                     classPath: Agg[os.Path],
                     jvmArgs: Seq[String] = Seq.empty,
                     envArgs: Map[String, String] = Map.empty,
                     mainArgs: Seq[String] = Seq.empty,
                     workingDir: os.Path = null,
                     streamOut: Boolean = true)
                    (implicit ctx: Ctx) = {

    val commandArgs =
      Vector("java") ++
      jvmArgs ++
      Vector("-cp", classPath.mkString(File.pathSeparator), mainClass) ++
      mainArgs

    val workingDir1 = Option(workingDir).getOrElse(ctx.dest)
    os.makeDir.all(workingDir1)

    os.proc(commandArgs).call(cwd = workingDir1, env = envArgs)
  }

  /**
    * Runs a JVM subprocess with the given configuration and streams
    * it's stdout and stderr to the console.
    */
  def runSubprocess(mainClass: String,
                    classPath: Agg[os.Path],
                    jvmArgs: Seq[String] = Seq.empty,
                    envArgs: Map[String, String] = Map.empty,
                    mainArgs: Seq[String] = Seq.empty,
                    workingDir: os.Path = null,
                    background: Boolean = false): Unit = {
    val args =
      Vector("java") ++
      jvmArgs ++
      Vector("-cp", classPath.mkString(File.pathSeparator), mainClass) ++
      mainArgs

    if (background) spawnSubprocess(args, envArgs, workingDir)
    else runSubprocess(args, envArgs, workingDir)
  }

  @deprecated("Use runSubprocess instead")
  def baseInteractiveSubprocess(commandArgs: Seq[String],
                                envArgs: Map[String, String],
                                workingDir: os.Path) = {
    runSubprocess(commandArgs, envArgs, workingDir)
  }

  /**
    * Runs a generic subprocess and waits for it to terminate.
    */
  def runSubprocess(commandArgs: Seq[String],
                    envArgs: Map[String, String],
                    workingDir: os.Path) = {
    val process = spawnSubprocess(commandArgs, envArgs, workingDir)

    process.waitFor()
    if (process.exitCode() == 0) ()
    else throw new Exception("Interactive Subprocess Failed")
  }

  /**
    * Spawns a generic subprocess, streaming the stdout and stderr to the
    * console. If the System.out/System.err have been substituted, makes sure
    * that the subprocess's stdout and stderr streams go to the subtituted
    * streams
    */
  def spawnSubprocess(commandArgs: Seq[String],
                      envArgs: Map[String, String],
                      workingDir: os.Path) = {
    // If System.in is fake, then we pump output manually rather than relying
    // on `os.Inherit`. That is because `os.Inherit` does not follow changes
    // to System.in/System.out/System.err, so the subprocess's streams get sent
    // to the parent process's origin outputs even if we want to direct them
    // elsewhere
    if (System.in.isInstanceOf[ByteArrayInputStream]){
      val process = os.proc(commandArgs).spawn(
        cwd = workingDir,
        env = envArgs,
        stdin = os.Pipe,
        stdout = os.Pipe,
        stderr = os.Pipe
      )

      val sources = Seq(
        process.stdout -> System.out,
        process.stderr -> System.err,
        System.in -> process.stdin
      )

      for((std, dest) <- sources){
        new Thread(new InputPumper(std, dest, false)).start()
      }

      process
    }else{
      os.proc(commandArgs).spawn(
        cwd = workingDir,
        env = envArgs,
        stdin = os.Inherit,
        stdout = os.Inherit,
        stderr = os.Inherit
      )
    }
  }


  def runLocal(mainClass: String,
               classPath: Agg[os.Path],
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


  def inprocess[T](classPath: Agg[os.Path],
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
    * @param inputPaths - `Agg` of `os.Path`s containing files to be included in the jar
    * @param mainClass - optional main class for the jar
    * @param fileFilter - optional file filter to select files to be included.
    *                   Given a `os.Path` (from inputPaths) and a `os.RelPath` for the individual file,
    *                   return true if the file is to be included in the jar.
    * @param ctx - implicit `Ctx.Dest` used to determine the output directory for the jar.
    * @return - a `PathRef` for the created jar.
    */
  def createJar(inputPaths: Agg[os.Path],
                mainClass: Option[String] = None,
                fileFilter: (os.Path, os.RelPath) => Boolean = (p: os.Path, r: os.RelPath) => true)
               (implicit ctx: Ctx.Dest): PathRef = {
    val outputPath = ctx.dest / "out.jar"
    os.remove.all(outputPath)

    val seen = mutable.Set.empty[os.RelPath]
    seen.add(os.rel / "META-INF" / "MANIFEST.MF")
    val jar = new JarOutputStream(
      new FileOutputStream(outputPath.toIO),
      createManifest(mainClass)
    )

    try{
      assert(inputPaths.forall(os.exists(_)))
      for{
        p <- inputPaths
        (file, mapping) <-
          if (os.isFile(p)) Iterator(p -> os.rel / p.last)
          else os.walk(p).filter(os.isFile).map(sub => sub -> sub.relativeTo(p)).sorted
        if !seen(mapping) && fileFilter(p, mapping)
      } {
        seen.add(mapping)
        val entry = new JarEntry(mapping.toString)
        entry.setTime(os.mtime(file))
        jar.putNextEntry(entry)
        jar.write(os.read.bytes(file))
        jar.closeEntry()
      }
    } finally {
      jar.close()
    }

    PathRef(outputPath)
  }

  def createAssembly(inputPaths: Agg[os.Path],
                     mainClass: Option[String] = None,
                     prependShellScript: String = "",
                     base: Option[os.Path] = None,
                     assemblyRules: Seq[Assembly.Rule] = Assembly.defaultRules)
                    (implicit ctx: Ctx.Dest with Ctx.Log): PathRef = {

    val tmp = ctx.dest / "out-tmp.jar"

    val baseUri = "jar:" + tmp.toIO.getCanonicalFile.toURI.toASCIIString
    val hm = new java.util.HashMap[String, String]()

    base match{
      case Some(b) => os.copy(b, tmp)
      case None => hm.put("create", "true")
    }

    val zipFs = FileSystems.newFileSystem(URI.create(baseUri), hm)

    val manifest = createManifest(mainClass)
    val manifestPath = zipFs.getPath(JarFile.MANIFEST_NAME)
    Files.createDirectories(manifestPath.getParent)
    val manifestOut = Files.newOutputStream(
      manifestPath,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.CREATE
    )
    manifest.write(manifestOut)
    manifestOut.close()

    Assembly.groupAssemblyEntries(inputPaths, assemblyRules).view
      .foreach {
        case (mapping, AppendEntry(entries)) =>
          val path = zipFs.getPath(mapping).toAbsolutePath
          val concatenated = new SequenceInputStream(
            Collections.enumeration(entries.map(_.inputStream).asJava))
          writeEntry(path, concatenated, append = true)
        case (mapping, WriteOnceEntry(entry)) =>
          val path = zipFs.getPath(mapping).toAbsolutePath
          if (Files.notExists(path)) {
            writeEntry(path, entry.inputStream, append = false)
          }
      }

    zipFs.close()
    val output = ctx.dest / "out.jar"

    // Prepend shell script and make it executable
    if (prependShellScript.isEmpty) os.move(tmp, output)
    else{
      val lineSep = if (!prependShellScript.endsWith("\n")) "\n\r\n" else ""
      os.write(
        output,
        Seq[os.Source](
          prependShellScript + lineSep,
          os.read.inputStream(tmp)
        )
      )

      if (!scala.util.Properties.isWin) {
        os.perms.set(
          output,
          os.perms(output)
            + PosixFilePermission.GROUP_EXECUTE
            + PosixFilePermission.OWNER_EXECUTE
            + PosixFilePermission.OTHERS_EXECUTE
        )
      }
    }

    PathRef(output)
  }

  private def writeEntry(p: java.nio.file.Path, is: InputStream, append: Boolean): Unit = {
    if (p.getParent != null) Files.createDirectories(p.getParent)
    val options =
      if(append) Seq(StandardOpenOption.APPEND, StandardOpenOption.CREATE)
      else Seq(StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE)

    val outputStream = java.nio.file.Files.newOutputStream(p, options:_*)
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
                     classPath: Agg[os.Path],
                     jvmArgs: Seq[String])
                    (implicit ctx: Ctx.Dest)= {
    val isWin = scala.util.Properties.isWin
    val isBatch = isWin &&
      !(org.jline.utils.OSUtils.IS_CYGWIN
        || org.jline.utils.OSUtils.IS_MINGW
        || "MSYS" == System.getProperty("MSYSTEM"))
    val outputPath = ctx.dest / (if (isBatch) "run.bat" else "run")
    val classPathStrs = classPath.map(_.toString)

    os.write(outputPath, launcherUniversalScript(mainClass, classPathStrs, classPathStrs, jvmArgs))

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
          successes.map(p => PathRef(os.Path(p), quick = true)).filter(_.path.ext == "jar")
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
