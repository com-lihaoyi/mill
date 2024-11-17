package mill.util

import mill.api.Loose.Agg
import mill.api._
import mill.main.client.ServerFiles
import os.{ProcessOutput, SubProcess}

import java.io._
import java.lang.reflect.Modifier
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.Files
import scala.util.Properties.isWin
import os.CommandResult

object Jvm extends CoursierSupport {

  /**
   * Runs a JVM subprocess with the given configuration and returns a
   * [[os.CommandResult]] with it's aggregated output and error streams
   */
  def callSubprocess(
      mainClass: String,
      classPath: Agg[os.Path],
      jvmArgs: Seq[String] = Seq.empty,
      envArgs: Map[String, String] = Map.empty,
      mainArgs: Seq[String] = Seq.empty,
      workingDir: os.Path = null,
      streamOut: Boolean = true,
      check: Boolean = true,
      javaHome: Option[os.Path] = None
  )(implicit ctx: Ctx): CommandResult = {

    val commandArgs =
      Vector(javaExe(javaHome)) ++
        jvmArgs ++
        Vector("-cp", classPath.iterator.mkString(java.io.File.pathSeparator), mainClass) ++
        mainArgs

    val workingDir1 = Option(workingDir).getOrElse(ctx.dest)
    os.makeDir.all(workingDir1)

    os.proc(commandArgs)
      .call(
        cwd = workingDir1,
        env = envArgs,
        check = check,
        stdout = if (streamOut) os.Inherit else os.Pipe
      )
  }

  /**
   * Runs a JVM subprocess with the given configuration and returns a
   * [[os.CommandResult]] with it's aggregated output and error streams
   */
  def callSubprocess(
      mainClass: String,
      classPath: Agg[os.Path],
      jvmArgs: Seq[String],
      envArgs: Map[String, String],
      mainArgs: Seq[String],
      workingDir: os.Path,
      streamOut: Boolean
  )(implicit ctx: Ctx): CommandResult = {
    callSubprocess(mainClass, classPath, jvmArgs, envArgs, mainArgs, workingDir, streamOut, true)
  }

  /**
   * Resolves a tool to a path under the currently used JDK (if known).
   */
  def jdkTool(toolName: String, javaHome: Option[os.Path]): String = {
    javaHome
      .map(_.toString())
      .orElse(sys.props.get("java.home"))
      .map(h =>
        if (isWin) new File(h, s"bin\\${toolName}.exe")
        else new File(h, s"bin/${toolName}")
      )
      .filter(f => f.exists())
      .fold(toolName)(_.getAbsolutePath())

  }

  def jdkTool(toolName: String): String = jdkTool(toolName, None)

  def javaExe(javaHome: Option[os.Path]): String = jdkTool("java", javaHome)

  def javaExe: String = javaExe(None)

  def defaultBackgroundOutputs(outputDir: os.Path): Option[(ProcessOutput, ProcessOutput)] =
    Some((outputDir / "stdout.log", outputDir / "stderr.log"))

  /**
   * Runs a JVM subprocess with the given configuration and streams
   * it's stdout and stderr to the console.
   * @param mainClass The main class to run
   * @param classPath The classpath
   * @param jvmArgs Arguments given to the forked JVM
   * @param envArgs Environment variables used when starting the forked JVM
   * @param workingDir The working directory to be used by the forked JVM
   * @param background `true` if the forked JVM should be spawned in background
   * @param useCpPassingJar When `false`, the `-cp` parameter is used to pass the classpath
   *                        to the forked JVM.
   *                        When `true`, a temporary empty JAR is created
   *                        which contains a `Class-Path` manifest entry containing the actual classpath.
   *                        This might help with long classpaths on OS'es (like Windows)
   *                        which only supports limited command-line length
   */
  def runSubprocess(
      mainClass: String,
      classPath: Agg[os.Path],
      jvmArgs: Seq[String] = Seq.empty,
      envArgs: Map[String, String] = Map.empty,
      mainArgs: Seq[String] = Seq.empty,
      workingDir: os.Path = null,
      background: Boolean = false,
      useCpPassingJar: Boolean = false,
      runBackgroundLogToConsole: Boolean = false,
      javaHome: Option[os.Path] = None
  )(implicit ctx: Ctx): Unit = {
    runSubprocessWithBackgroundOutputs(
      mainClass,
      classPath,
      jvmArgs,
      envArgs,
      mainArgs,
      workingDir,
      if (!background) None
      else if (runBackgroundLogToConsole) {
        val pwd0 = os.Path(java.nio.file.Paths.get(".").toAbsolutePath)
        // Hack to forward the background subprocess output to the Mill server process
        // stdout/stderr files, so the output will get properly slurped up by the Mill server
        // and shown to any connected Mill client even if the current command has completed
        Some(
          (
            os.PathAppendRedirect(pwd0 / ".." / ServerFiles.stdout),
            os.PathAppendRedirect(pwd0 / ".." / ServerFiles.stderr)
          )
        )
      } else Jvm.defaultBackgroundOutputs(ctx.dest),
      useCpPassingJar,
      javaHome
    )
  }

  // bincompat shim
  def runSubprocess(
      mainClass: String,
      classPath: Agg[os.Path],
      jvmArgs: Seq[String],
      envArgs: Map[String, String],
      mainArgs: Seq[String],
      workingDir: os.Path,
      background: Boolean,
      useCpPassingJar: Boolean
  )(implicit ctx: Ctx): Unit =
    runSubprocess(
      mainClass,
      classPath,
      jvmArgs,
      envArgs,
      mainArgs,
      workingDir,
      background,
      useCpPassingJar
    )

  /**
   * Runs a JVM subprocess with the given configuration and streams
   * it's stdout and stderr to the console.
   * @param mainClass The main class to run
   * @param classPath The classpath
   * @param jvmArgs Arguments given to the forked JVM
   * @param envArgs Environment variables used when starting the forked JVM
   * @param workingDir The working directory to be used by the forked JVM
   * @param backgroundOutputs If the subprocess should run in the background, a Tuple of ProcessOutputs containing out and err respectively. Specify None for nonbackground processes.
   * @param useCpPassingJar When `false`, the `-cp` parameter is used to pass the classpath
   *                        to the forked JVM.
   *                        When `true`, a temporary empty JAR is created
   *                        which contains a `Class-Path` manifest entry containing the actual classpath.
   *                        This might help with long classpaths on OS'es (like Windows)
   *                        which only supports limited command-line length
   */
  def runSubprocessWithBackgroundOutputs(
      mainClass: String,
      classPath: Agg[os.Path],
      jvmArgs: Seq[String] = Seq.empty,
      envArgs: Map[String, String] = Map.empty,
      mainArgs: Seq[String] = Seq.empty,
      workingDir: os.Path = null,
      backgroundOutputs: Option[Tuple2[ProcessOutput, ProcessOutput]] = None,
      useCpPassingJar: Boolean = false,
      javaHome: Option[os.Path] = None
  )(implicit ctx: Ctx): Unit = {

    pprint.log(javaHome)
    val cp =
      if (useCpPassingJar && !classPath.iterator.isEmpty) {
        val passingJar = os.temp(prefix = "run-", suffix = ".jar", deleteOnExit = false)
        ctx.log.debug(
          s"Creating classpath passing jar '${passingJar}' with Class-Path: ${classPath.iterator.map(
              _.toNIO.toUri().toURL().toExternalForm()
            ).mkString(" ")}"
        )
        createClasspathPassingJar(passingJar, classPath)
        Agg(passingJar)
      } else {
        classPath
      }

    val cpArgument = if (cp.nonEmpty) {
      Vector("-cp", cp.iterator.mkString(java.io.File.pathSeparator))
    } else Seq.empty
    val mainClassArgument = if (mainClass.nonEmpty) {
      Seq(mainClass)
    } else Seq.empty
    val args =
      Vector(javaExe(javaHome)) ++
        jvmArgs ++
        cpArgument ++
        mainClassArgument ++
        mainArgs

    ctx.log.debug(s"Run subprocess with args: ${args.map(a => s"'${a}'").mkString(" ")}")

    if (backgroundOutputs.nonEmpty)
      spawnSubprocessWithBackgroundOutputs(args, envArgs, workingDir, backgroundOutputs)
    else
      runSubprocess(args, envArgs, workingDir)
  }

  // bincompat shim
  def runSubprocessWithBackgroundOutputs(
      mainClass: String,
      classPath: Agg[os.Path],
      jvmArgs: Seq[String],
      envArgs: Map[String, String],
      mainArgs: Seq[String],
      workingDir: os.Path,
      backgroundOutputs: Option[Tuple2[ProcessOutput, ProcessOutput]],
      useCpPassingJar: Boolean
  )(implicit ctx: Ctx): Unit =
    runSubprocessWithBackgroundOutputs(
      mainClass,
      classPath,
      jvmArgs,
      envArgs,
      mainArgs,
      workingDir,
      backgroundOutputs,
      useCpPassingJar,
      None
    )(ctx)

  /**
   * Runs a generic subprocess and waits for it to terminate. If process exited with non-zero code, exception
   * will be thrown. If you want to manually handle exit code, check [[runSubprocessWithResult]]
   */
  def runSubprocess(
      commandArgs: Seq[String],
      envArgs: Map[String, String],
      workingDir: os.Path
  ): Unit = {
    runSubprocessWithResult(commandArgs, envArgs, workingDir).getOrThrow
    ()
  }

  /**
   * Runs a generic subprocess and waits for it to terminate.
   *
   * @return Result with exit code.
   */
  def runSubprocessWithResult(
      commandArgs: Seq[String],
      envArgs: Map[String, String],
      workingDir: os.Path
  ): Result[Int] = {
    val process = spawnSubprocessWithBackgroundOutputs(
      commandArgs,
      envArgs,
      workingDir,
      backgroundOutputs = None
    )
    val shutdownHook = new Thread("subprocess-shutdown") {
      override def run(): Unit = {
        System.err.println("Host JVM shutdown. Forcefully destroying subprocess ...")
        process.destroy()
      }
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    try {
      process.waitFor()
    } catch {
      case e: InterruptedException =>
        System.err.println("Interrupted. Forcefully destroying subprocess ...")
        process.destroy()
        // rethrow
        throw e
    } finally {
      Runtime.getRuntime().removeShutdownHook(shutdownHook)
    }
    if (process.exitCode() == 0) Result.Success(process.exitCode())
    else Result.Failure(
      "Interactive Subprocess Failed (exit code " + process.exitCode() + ")",
      Some(process.exitCode())
    )
  }

  /**
   * Spawns a generic subprocess, streaming the stdout and stderr to the
   * console. If the System.out/System.err have been substituted, makes sure
   * that the subprocess's stdout and stderr streams go to the substituted
   * streams.
   */
  def spawnSubprocess(
      commandArgs: Seq[String],
      envArgs: Map[String, String],
      workingDir: os.Path,
      background: Boolean = false
  ): SubProcess = {
    // XXX: workingDir is perhaps not the best choice for outputs, but absent a Ctx, we have
    //      no other place to choose.
    val backgroundOutputs = if (background) defaultBackgroundOutputs(workingDir) else None
    spawnSubprocessWithBackgroundOutputs(commandArgs, envArgs, workingDir, backgroundOutputs)
  }

  /**
   * Spawns a generic subprocess, streaming the stdout and stderr to the
   * console. If the System.out/System.err have been substituted, makes sure
   * that the subprocess's stdout and stderr streams go to the substituted
   * streams.
   *
   * If the process should be spawned in the background, destination streams for out and err
   * respectively must be defined in the backgroundOutputs tuple. Non-background process should set
   * backgroundOutputs to [[None]].
   */
  def spawnSubprocessWithBackgroundOutputs(
      commandArgs: Seq[String],
      envArgs: Map[String, String],
      workingDir: os.Path,
      backgroundOutputs: Option[Tuple2[ProcessOutput, ProcessOutput]] = None
  ): SubProcess = {
    os.proc(commandArgs).spawn(
      cwd = workingDir,
      env = envArgs,
      stdin = if (backgroundOutputs.isEmpty) os.Inherit else "",
      stdout = backgroundOutputs.map(_._1).getOrElse(os.Inherit),
      stderr = backgroundOutputs.map(_._2).getOrElse(os.Inherit)
    )
  }

  def runLocal(
      mainClass: String,
      classPath: Agg[os.Path],
      mainArgs: Seq[String] = Seq.empty
  )(implicit ctx: Ctx): Unit = {
    inprocess(
      classPath,
      classLoaderOverrideSbtTesting = false,
      isolated = true,
      closeContextClassLoaderWhenDone = true,
      cl => {
        getMainMethod(mainClass, cl).invoke(null, mainArgs.toArray)
      }
    )
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

  def runClassloader[T](classPath: Agg[os.Path])(body: ClassLoader => T)(implicit
      ctx: mill.api.Ctx.Home
  ): T = {
    inprocess(
      classPath,
      classLoaderOverrideSbtTesting = false,
      isolated = true,
      closeContextClassLoaderWhenDone = true,
      body
    )
  }
  def inprocess[T](
      classPath: Agg[os.Path],
      classLoaderOverrideSbtTesting: Boolean,
      isolated: Boolean,
      closeContextClassLoaderWhenDone: Boolean,
      body: ClassLoader => T
  )(implicit ctx: mill.api.Ctx.Home): T = {
    val urls = classPath.map(_.toIO.toURI.toURL)
    val cl =
      if (classLoaderOverrideSbtTesting) {
        mill.api.ClassLoader.create(
          urls.iterator.toVector,
          null,
          sharedPrefixes = Seq("sbt.testing.")
        )
      } else if (isolated) {
        mill.api.ClassLoader.create(urls.iterator.toVector, null)
      } else {
        mill.api.ClassLoader.create(urls.iterator.toVector, getClass.getClassLoader)
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

  def createManifest(mainClass: Option[String]): mill.api.JarManifest = {
    mainClass.foldLeft(mill.api.JarManifest.MillDefault)((m, c) =>
      m.add((java.util.jar.Attributes.Name.MAIN_CLASS.toString, c))
    )
  }

  /**
   * Create a jar file containing all files from the specified input Paths,
   * called out.jar in the implicit ctx.dest folder. An optional main class may
   * be provided for the jar. An optional filter function may also be provided to
   * selectively include/exclude specific files.
   * @param inputPaths - `Agg` of `os.Path`s containing files to be included in the jar
   * @param fileFilter - optional file filter to select files to be included.
   *                   Given an `os.Path` (from inputPaths) and an `os.RelPath` for the individual file,
   *                   return true if the file is to be included in the jar.
   * @param ctx - implicit `Ctx.Dest` used to determine the output directory for the jar.
   * @return - a `PathRef` for the created jar.
   */
  def createJar(
      inputPaths: Agg[os.Path],
      manifest: mill.api.JarManifest = mill.api.JarManifest.MillDefault,
      fileFilter: (os.Path, os.RelPath) => Boolean = (_, _) => true
  )(implicit ctx: Ctx.Dest): PathRef = {
    val outputPath = ctx.dest / "out.jar"
    createJar(
      jar = outputPath,
      inputPaths = inputPaths,
      manifest = manifest,
      fileFilter = fileFilter
    )
    PathRef(outputPath)
  }

  def createJar(
      jar: os.Path,
      inputPaths: Agg[os.Path],
      manifest: mill.api.JarManifest,
      fileFilter: (os.Path, os.RelPath) => Boolean
  ): Unit =
    JarOps.jar(jar, inputPaths, manifest, fileFilter, includeDirs = true, timestamp = None)

  def createClasspathPassingJar(jar: os.Path, classpath: Agg[os.Path]): Unit = {
    createJar(
      jar = jar,
      inputPaths = Agg(),
      manifest = mill.api.JarManifest.MillDefault.add(
        "Class-Path" -> classpath.iterator.map(_.toNIO.toUri().toURL().toExternalForm()).mkString(
          " "
        )
      ),
      fileFilter = (_, _) => true
    )
  }

  def universalScript(
      shellCommands: String,
      cmdCommands: String,
      shebang: Boolean = false
  ): String = {
    Seq(
      if (shebang) "#!/usr/bin/env sh" else "",
      "@ 2>/dev/null # 2>nul & echo off & goto BOF\r",
      ":",
      shellCommands.replaceAll("\r\n|\n", "\n"),
      "exit",
      Seq(
        "",
        ":BOF",
        "setlocal",
        "@echo off",
        cmdCommands.replaceAll("\r\n|\n", "\r\n"),
        "endlocal",
        "exit /B %errorlevel%",
        ""
      ).mkString("\r\n")
    ).filterNot(_.isEmpty).mkString("\n")
  }

  def launcherUniversalScript(
      mainClass: String,
      shellClassPath: Agg[String],
      cmdClassPath: Agg[String],
      jvmArgs: Seq[String],
      shebang: Boolean = false
  ): String = {
    universalScript(
      shellCommands =
        s"""exec java ${jvmArgs.mkString(" ")} $$JAVA_OPTS -cp "${shellClassPath.iterator.mkString(
            ":"
          )}" '$mainClass' "$$@"""",
      cmdCommands =
        s"""java ${jvmArgs.mkString(" ")} %JAVA_OPTS% -cp "${cmdClassPath.iterator.mkString(
            ";"
          )}" $mainClass %*""",
      shebang = shebang
    )
  }
  def createLauncher(mainClass: String, classPath: Agg[os.Path], jvmArgs: Seq[String])(implicit
      ctx: Ctx.Dest
  ): PathRef = {
    val isWin = scala.util.Properties.isWin
    val isBatch =
      isWin && !(org.jline.utils.OSUtils.IS_CYGWIN || org.jline.utils.OSUtils.IS_MSYSTEM)
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

  @deprecated("Use mill.api.JarManifest instead", "Mill after 0.11.0-M4")
  type JarManifest = mill.api.JarManifest
  @deprecated("Use mill.api.JarManifest instead", "Mill after 0.11.0-M4")
  val JarManifest = mill.api.JarManifest

}
