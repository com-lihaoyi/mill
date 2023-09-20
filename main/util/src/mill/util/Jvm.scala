package mill.util

import mill.api.Loose.Agg
import mill.api._
import mill.main.client.InputPumper
import os.{ProcessOutput, SubProcess}

import java.io._
import java.lang.reflect.Modifier
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.Files
import scala.util.Properties.isWin

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
      streamOut: Boolean = true
  )(implicit ctx: Ctx) = {

    val commandArgs =
      Vector(javaExe) ++
        jvmArgs ++
        Vector("-cp", classPath.iterator.mkString(java.io.File.pathSeparator), mainClass) ++
        mainArgs

    val workingDir1 = Option(workingDir).getOrElse(ctx.dest)
    os.makeDir.all(workingDir1)

    os.proc(commandArgs).call(cwd = workingDir1, env = envArgs)
  }

  /**
   * Resolves a tool to a path under the currently used JDK (if known).
   */
  def jdkTool(toolName: String): String = {
    sys.props
      .get("java.home")
      .map(h =>
        if (isWin) new File(h, s"bin\\${toolName}.exe")
        else new File(h, s"bin/${toolName}")
      )
      .filter(f => f.exists())
      .fold(toolName)(_.getAbsolutePath())

  }

  def javaExe: String = jdkTool("java")

  def defaultBackgroundOutputs(outputDir: os.Path): Option[(ProcessOutput, ProcessOutput)] =
    Some((outputDir / "stdout.log", outputDir / "stderr.log"))

  /**
   * Runs a JVM subprocess with the given configuration and streams
   * it's stdout and stderr to the console.
   * @param mainClass The main class to run
   * @param classPath The classpath
   * @param JvmArgs Arguments given to the forked JVM
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
      useCpPassingJar: Boolean = false
  )(implicit ctx: Ctx): Unit = {
    runSubprocessWithBackgroundOutputs(
      mainClass,
      classPath,
      jvmArgs,
      envArgs,
      mainArgs,
      workingDir,
      if (background) defaultBackgroundOutputs(ctx.dest) else None,
      useCpPassingJar
    )
  }

  /**
   * Runs a JVM subprocess with the given configuration and streams
   * it's stdout and stderr to the console.
   * @param mainClass The main class to run
   * @param classPath The classpath
   * @param JvmArgs Arguments given to the forked JVM
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
      useCpPassingJar: Boolean = false
  )(implicit ctx: Ctx): Unit = {

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

    val args =
      Vector(javaExe) ++
        jvmArgs ++
        Vector("-cp", cp.iterator.mkString(java.io.File.pathSeparator), mainClass) ++
        mainArgs

    ctx.log.debug(s"Run subprocess with args: ${args.map(a => s"'${a}'").mkString(" ")}")

    if (backgroundOutputs.nonEmpty)
      spawnSubprocessWithBackgroundOutputs(args, envArgs, workingDir, backgroundOutputs)
    else
      runSubprocess(args, envArgs, workingDir)
  }

  /**
   * Runs a generic subprocess and waits for it to terminate.
   */
  def runSubprocess(commandArgs: Seq[String], envArgs: Map[String, String], workingDir: os.Path) = {
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
    if (process.exitCode() == 0) ()
    else throw new Exception("Interactive Subprocess Failed (exit code " + process.exitCode() + ")")
  }

  /**
   * Spawns a generic subprocess, streaming the stdout and stderr to the
   * console. If the System.out/System.err have been substituted, makes sure
   * that the subprocess's stdout and stderr streams go to the subtituted
   * streams
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
   * that the subprocess's stdout and stderr streams go to the subtituted
   * streams.
   *
   * If the process should be spawned in the background, destination streams for out and err
   * respectively must be defined in the backgroundOutputs tuple. Nonbackground process should set
   * backgroundOutputs to None
   */
  def spawnSubprocessWithBackgroundOutputs(
      commandArgs: Seq[String],
      envArgs: Map[String, String],
      workingDir: os.Path,
      backgroundOutputs: Option[Tuple2[ProcessOutput, ProcessOutput]] = None
  ): SubProcess = {
    // If System.in is fake, then we pump output manually rather than relying
    // on `os.Inherit`. That is because `os.Inherit` does not follow changes
    // to System.in/System.out/System.err, so the subprocess's streams get sent
    // to the parent process's origin outputs even if we want to direct them
    // elsewhere

    if (!SystemStreams.isOriginal()) {
      val process = os.proc(commandArgs).spawn(
        cwd = workingDir,
        env = envArgs,
        stdin = if (backgroundOutputs.isEmpty) os.Pipe else "",
        stdout = backgroundOutputs.map(_._1).getOrElse(os.Pipe),
        stderr = backgroundOutputs.map(_._2).getOrElse(os.Pipe)
      )

      val sources = Seq(
        (process.stdout, System.out, "spawnSubprocess.stdout", false, () => true),
        (process.stderr, System.err, "spawnSubprocess.stderr", false, () => true),
        (System.in, process.stdin, "spawnSubprocess.stdin", true, () => process.isAlive())
      )

      for ((std, dest, name, checkAvailable, runningCheck) <- sources) {
        val t = new Thread(
          new InputPumper(std, dest, checkAvailable, () => runningCheck()),
          name
        )
        t.setDaemon(true)
        t.start()
      }

      process
    } else {
      os.proc(commandArgs).spawn(
        cwd = workingDir,
        env = envArgs,
        stdin = if (backgroundOutputs.isEmpty) os.Inherit else "",
        stdout = backgroundOutputs.map(_._1).getOrElse(os.Inherit),
        stderr = backgroundOutputs.map(_._2).getOrElse(os.Inherit)
      )
    }
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
   *                   Given a `os.Path` (from inputPaths) and a `os.RelPath` for the individual file,
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
