package mill.launcher

import io.github.alexarchambault.nativeterm.NativeTerminal
import mill.api.internal.OneOrMore
import mill.client.ClientUtil
import mill.constants.*

import java.io.File
import java.util.UUID
import scala.jdk.CollectionConverters._

object MillProcessLauncher {

  def launchMillNoDaemon(
      args: Seq[String],
      outMode: OutFolderMode,
      runnerClasspath: Seq[os.Path],
      mainClass: String,
      useFileLocks: Boolean,
      workDir: os.Path,
      env: Map[String, String],
      millRepositories: Seq[String]
  ): Int = {
    val sig = f"${UUID.randomUUID().hashCode}%08x"
    val processDir =
      os.Path(OutFiles.OutFiles.outFor(outMode), workDir) / OutFiles.OutFiles.millNoDaemon / sig

    prepareMillRunFolder(processDir)

    val userPropsSeq = ClientUtil.getUserSetProperties().map { case (k, v) => s"-D$k=$v" }.toSeq

    val cmd = millLaunchJvmCommand(runnerClasspath, outMode, workDir, millRepositories) ++
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
      env: Map[String, String],
      millRepositories: Seq[String]
  ): os.SubProcess = {
    val cmd = millLaunchJvmCommand(runnerClasspath, outMode, workDir, millRepositories) ++
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
    val env = mill.internal.Util.envForInterpolation(workDir)

    if (os.exists(configFile)) {
      ClientUtil.readOptsFileLines(configFile, env)
    } else {
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
    mill.internal.Util
      .parseBuildHeaderValue[OneOrMore[String]](headerData, key, default = OneOrMore(Nil))
      .value
      .map(item => Util.interpolateEnvVars(item, env.asJava))
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

  def javaHome(
      outMode: OutFolderMode,
      workDir: os.Path,
      millRepositories: Seq[String]
  ): Option[os.Path] = {
    val jvmVersion = loadMillConfig(ConfigConstants.millJvmVersion, workDir)
      .headOption
      .getOrElse(BuildInfo.defaultJvmVersion)

    val jvmIndexVersion = loadMillConfig(ConfigConstants.millJvmIndexVersion, workDir).headOption

    // Handle "system" specially - return None to use PATH-based Java lookup
    // (javaExe returns "java" when javaHome is None, using PATH lookup)
    if (jvmVersion == "system") None
    else Option.when(jvmVersion != null) {
      CoursierClient.resolveJavaHome(jvmVersion, jvmIndexVersion, outMode, millRepositories)
    }
  }

  def javaExe(
      outMode: OutFolderMode,
      workDir: os.Path,
      millRepositories: Seq[String]
  ): String = {
    javaHome(outMode, workDir, millRepositories) match {
      case None => "java"
      case Some(home) =>
        val exeName = if (isWin) "java.exe" else "java"
        (home / "bin" / exeName).toString
    }
  }

  def millLaunchJvmCommand(
      runnerClasspath: Seq[os.Path],
      outMode: OutFolderMode,
      workDir: os.Path,
      millRepositories: Seq[String]
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

    Seq(javaExe(outMode, workDir, millRepositories)) ++
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

  /** Get current terminal dimensions. Returns TerminalDimsResult with width/height. */
  def getTerminalDims(): DaemonRpc.TerminalDimsResult = {
    try {
      if (!Util.hasConsole()) DaemonRpc.TerminalDimsResult(None, None)
      else getNativeTerminalSize() match {
        case Some((width, height)) => DaemonRpc.TerminalDimsResult(Some(width), Some(height))
        case None if !tputExists => DaemonRpc.TerminalDimsResult(Some(78), Some(24))
        case None =>
          val width =
            try Some(getTerminalDim("cols", inheritError = true))
            catch { case _: Exception => None }
          val height =
            try Some(getTerminalDim("lines", inheritError = true))
            catch { case _: Exception => None }
          DaemonRpc.TerminalDimsResult(width, height)
      }
    } catch {
      case _: Exception => DaemonRpc.TerminalDimsResult(None, None)
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
    os.makeDir.all(daemonDir / DaemonFiles.sandbox)
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
