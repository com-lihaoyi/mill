package mill.launcher

import io.github.alexarchambault.nativeterm.NativeTerminal
import mill.api.daemon.MillException
import mill.client.ClientUtil
import mill.constants.*

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

object MillProcessLauncher {

  def launchMillNoDaemon(
      args: Seq[String],
      outMode: OutFolderMode,
      runnerClasspath: Seq[os.Path],
      mainClass: String,
      useFileLocks: Boolean,
      workDir: os.Path,
      env: Map[String, String]
  ): Int = {
    val sig = f"${UUID.randomUUID().hashCode}%08x"
    val processDir =
      os.Path(OutFiles.OutFiles.outFor(outMode), workDir) / OutFiles.OutFiles.millNoDaemon / sig

    prepareMillRunFolder(processDir)

    val userPropsSeq = ClientUtil.getUserSetProperties().map { case (k, v) => s"-D$k=$v" }.toSeq

    val cmd = millLaunchJvmCommand(runnerClasspath, outMode, workDir) ++
      userPropsSeq ++
      Seq(mainClass, processDir.toString, outMode.asString, useFileLocks.toString) ++
      loadMillConfig(ConfigConstants.millOpts, workDir) ++
      args

    var interrupted = false
    val proc = configureRunMillProcess(cmd, processDir, workDir = workDir, env = env)
    try {
      proc.waitFor()
      proc.exitCode()
    } catch {
      case e: InterruptedException =>
        interrupted = true
        throw e
    } finally {
      if (!interrupted && os.exists(processDir)) os.remove.all(processDir)
    }
  }

  def launchMillDaemon(
      daemonDir: os.Path,
      outMode: OutFolderMode,
      runnerClasspath: Seq[os.Path],
      useFileLocks: Boolean,
      workDir: os.Path,
      env: Map[String, String]
  ): os.SubProcess = {
    val cmd = millLaunchJvmCommand(runnerClasspath, outMode, workDir) ++
      Seq("mill.daemon.MillDaemonMain", daemonDir.toString, outMode.asString, useFileLocks.toString)

    configureRunMillProcess(
      cmd,
      daemonDir,
      stdout = daemonDir / DaemonFiles.stdout,
      stderr = daemonDir / DaemonFiles.stderr,
      workDir = workDir,
      env = env
    )
  }

  private def configureRunMillProcess(
      cmd: Seq[String],
      daemonDir: os.Path,
      stdin: os.ProcessInput = os.Inherit,
      stdout: os.ProcessOutput = os.Inherit,
      stderr: os.ProcessOutput = os.Inherit,
      workDir: os.Path,
      env: Map[String, String]
  ): os.SubProcess = {
    val sandbox = daemonDir / DaemonFiles.sandbox
    os.makeDir.all(sandbox)

    val processEnv = Map(
      EnvVars.MILL_WORKSPACE_ROOT -> workDir.toString,
      EnvVars.MILL_ENABLE_STATIC_CHECKS -> "true"
    ) ++ (
      if (env.contains(EnvVars.MILL_EXECUTABLE_PATH)) Map.empty
      else Map(EnvVars.MILL_EXECUTABLE_PATH -> getExecutablePath)
    ) ++ {
      val jdkJavaOptions = env.getOrElse("JDK_JAVA_OPTIONS", "")
      val javaOpts = env.getOrElse("JAVA_OPTS", "")
      val opts = s"$jdkJavaOptions $javaOpts".trim
      if (opts.nonEmpty) Map("JDK_JAVA_OPTIONS" -> opts) else Map.empty
    }

    // destroyOnExit = false to prevent the daemon from being killed when the Mill client exits.
    // The daemon is a long-lived background process that should survive client disconnections.
    os.proc(cmd).spawn(
      cwd = sandbox,
      env = processEnv,
      stdin = stdin,
      stdout = stdout,
      stderr = stderr,
      destroyOnExit = false
    )
  }

  def loadMillConfig(key: String, workDir: os.Path = os.pwd): Seq[String] = {
    val configFile = workDir / s".$key"
    val workspaceDir = workDir.toString

    // Build environment map for variable interpolation
    val env = sys.env ++ Map(
      // Hardcode support for PWD because the graal native launcher has it set to the
      // working dir of the enclosing process, when we want it to be set to the working
      // dir of the current process
      "PWD" -> workspaceDir,
      "WORKSPACE" -> workspaceDir,
      "MILL_VERSION" -> BuildInfo.millVersion,
      "MILL_BIN_PLATFORM" -> BuildInfo.millBinPlatform
    )

    if (os.exists(configFile)) {
      ClientUtil.readOptsFileLines(configFile, env)
    } else {
      import scala.jdk.CollectionConverters._
      CodeGenConstants.rootBuildFileNames.asScala.toSeq
        .map(name => workDir / name)
        .find(os.exists(_))
        .map { buildFile =>
          val fileName = buildFile.last
          val headerData = Util.readBuildHeader(buildFile.toNIO, fileName)
          parseConfigFromHeader(headerData, key, env)
        }
        .getOrElse(Seq.empty)
    }
  }

  private def parseConfigFromHeader(
      headerData: String,
      key: String,
      env: Map[String, String]
  ): Seq[String] = {
    import scala.jdk.CollectionConverters._
    mill.internal.Util.parseYaml0(
      "build header",
      headerData,
      upickle.default.reader[Map[String, ujson.Value]]
    ) match {
      case mill.api.Result.Success(conf) =>
        conf.get(key) match {
          case None => Seq.empty
          case Some(ujson.Arr(items)) =>
            items.map(item => Util.interpolateEnvVars(item.str, env.asJava)).toSeq
          case Some(other) =>
            Seq(Util.interpolateEnvVars(other.str, env.asJava))
        }
      case f: mill.api.Result.Failure =>
        throw new MillException(s"Failed parsing build header: ${f.error}")
    }
  }

  def millServerTimeout: Option[String] =
    sys.env.get(EnvVars.MILL_SERVER_TIMEOUT_MILLIS)

  /**
   * Computes a fingerprint of JVM options that affect daemon behavior.
   * This includes options from .mill-jvm-opts file and JAVA_OPTS/JDK_JAVA_OPTIONS env vars.
   * When this fingerprint changes, the daemon should be restarted.
   */
  def computeJvmOpts(workDir: os.Path, env: Map[String, String]): Seq[String] = {
    val configOpts = loadMillConfig(ConfigConstants.millJvmOpts, workDir)
    val javaOpts = mill.api.internal.ParseArgs.parseShellArgs(env.getOrElse("JAVA_OPTS", ""))
    val jdkJavaOptions =
      mill.api.internal.ParseArgs.parseShellArgs(env.getOrElse("JDK_JAVA_OPTIONS", ""))
    configOpts ++ javaOpts ++ jdkJavaOptions
  }

  def isWin: Boolean = System.getProperty("os.name", "").startsWith("Windows")

  def javaHome(outMode: OutFolderMode, workDir: os.Path = os.pwd): Option[os.Path] = {
    val jvmVersion = loadMillConfig(ConfigConstants.millJvmVersion, workDir)
      .headOption
      .getOrElse(BuildInfo.defaultJvmVersion)

    val jvmIndexVersion = loadMillConfig(ConfigConstants.millJvmIndexVersion, workDir).headOption

    // Handle "system" specially - return None to use PATH-based Java lookup
    // (javaExe returns "java" when javaHome is None, using PATH lookup)
    if (jvmVersion == "system") None
    else if (jvmVersion != null)
      Some(CoursierClient.resolveJavaHome(jvmVersion, jvmIndexVersion, outMode))
    else None
  }

  def javaExe(outMode: OutFolderMode, workDir: os.Path = os.pwd): String = {
    javaHome(outMode, workDir) match {
      case None => "java"
      case Some(home) =>
        val exeName = if (isWin) "java.exe" else "java"
        (home / "bin" / exeName).toString
    }
  }

  def millLaunchJvmCommand(
      runnerClasspath: Seq[os.Path],
      outMode: OutFolderMode,
      workDir: os.Path = os.pwd
  ): Seq[String] = {
    val millProps = sys.props.toSeq
      .filter(_._1.startsWith("MILL_"))
      .map { case (k, v) => s"-D$k=$v" }

    val serverTimeoutOpt = millServerTimeout.map(t => s"-Dmill.server_timeout=$t").toSeq

    // UTF-8 encoding options to fix Unicode character display issues on Windows
    val encodingOpts = Seq(
      "-Dfile.encoding=UTF-8",
      "-Dsun.stdout.encoding=UTF-8",
      "-Dsun.stderr.encoding=UTF-8"
    )

    Seq(javaExe(outMode, workDir)) ++
      millProps ++
      serverTimeoutOpt ++
      encodingOpts ++
      loadMillConfig(ConfigConstants.millJvmOpts, workDir) ++
      Seq("-cp", runnerClasspath.mkString(File.pathSeparator))
  }

  private def getTerminalDim(s: String, inheritError: Boolean): Int = {
    val result = os.proc("tput", s).call(
      stdin = os.Inherit,
      stdout = os.Pipe,
      stderr = if (inheritError) os.Inherit else os.Pipe,
      check = false
    )
    if (result.exitCode != 0) throw new Exception("tput failed")
    result.out.trim().toInt
  }

  private val memoizedTerminalDims = new AtomicReference[String]()

  // Returns native terminal size if available, avoiding calling getSize twice
  private def getNativeTerminalSize(): Option[(Int, Int)] = {
    JLineNativeLoader.initJLineNative()
    if (Util.hasConsole()) {
      try {
        val size = NativeTerminal.getSize
        Some((size.getWidth, size.getHeight))
      } catch {
        case _: Throwable => None
      }
    } else None
  }

  private def writeTerminalDims(daemonDir: os.Path): Unit = {
    val str =
      try {
        if (!Util.hasConsole()) "0 0"
        else getNativeTerminalSize() match {
          case Some((width, height)) => s"$width $height"
          case None if !tputExists =>
            // Hardcoded size of a quarter screen terminal on 13" windows laptop
            "78 24"
          case None =>
            s"${getTerminalDim("cols", inheritError = true)} ${getTerminalDim("lines", inheritError = true)}"
        }
      } catch {
        case _: Exception => "0 0"
      }

    // We memoize previously seen values to avoid causing lots
    // of upstream work if the value hasn't actually changed.
    // See https://github.com/com-lihaoyi/mill/discussions/4092
    val oldValue = memoizedTerminalDims.getAndSet(str)
    if (oldValue == null || oldValue != str) {
      os.write.over(daemonDir / DaemonFiles.terminfo, str)
    }
  }

  // Lazy so we only shell out to tput when canUseNativeTerminal is false
  private lazy val tputExists: Boolean = {
    try {
      getTerminalDim("cols", inheritError = false)
      getTerminalDim("lines", inheritError = false)
      true
    } catch {
      case _: Exception => false
    }
  }

  def prepareMillRunFolder(daemonDir: os.Path): Unit = {
    // Clear out run-related files from the server folder to make sure we
    // never hit issues where we are reading the files from a previous run
    os.remove(daemonDir / DaemonFiles.terminfo, checkExists = false)

    os.makeDir.all(daemonDir / DaemonFiles.sandbox)

    writeTerminalDims(daemonDir)

    val termInfoPropagatorThread = new Thread(
      () => {
        try {
          while (true) {
            writeTerminalDims(daemonDir)
            Thread.sleep(100)
          }
        } catch {
          case _: Exception =>
        }
      },
      "TermInfoPropagatorThread"
    )
    termInfoPropagatorThread.setDaemon(true)
    termInfoPropagatorThread.start()
  }

  def getExecutablePath: String = {
    try {
      os.Path(getClass.getProtectionDomain.getCodeSource.getLocation.toURI).toString
    } catch {
      case e: java.net.URISyntaxException =>
        throw new RuntimeException("Failed to determine Mill client executable path", e)
    }
  }
}
