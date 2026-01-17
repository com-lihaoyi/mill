package mill.launcher

import io.github.alexarchambault.nativeterm.NativeTerminal
import mill.client.ClientUtil
import mill.constants._

import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

object MillProcessLauncher {

  def launchMillNoDaemon(
      args: Seq[String],
      outMode: OutFolderMode,
      runnerClasspath: Seq[os.Path],
      mainClass: String,
      useFileLocks: Boolean
  ): Int = {
    val sig = f"${UUID.randomUUID().hashCode}%08x"
    val processDir =
      os.Path(OutFiles.OutFiles.outFor(outMode), os.pwd) / OutFiles.OutFiles.millNoDaemon / sig

    prepareMillRunFolder(processDir)

    val userPropsSeq = ClientUtil.getUserSetProperties().map { case (k, v) => s"-D$k=$v" }.toSeq

    val cmd = millLaunchJvmCommand(runnerClasspath) ++
      userPropsSeq ++
      Seq(mainClass, processDir.toString, outMode.asString, useFileLocks.toString) ++
      loadMillConfig(ConfigConstants.millOpts) ++
      args

    var interrupted = false
    try configureRunMillProcess(cmd, processDir).waitFor()
    catch {
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
      useFileLocks: Boolean
  ): Process = {
    val cmd = millLaunchJvmCommand(runnerClasspath) ++
      Seq("mill.daemon.MillDaemonMain", daemonDir.toString, outMode.asString, useFileLocks.toString)

    configureRunMillProcess(
      cmd,
      daemonDir,
      stdout = daemonDir / DaemonFiles.stdout,
      stderr = daemonDir / DaemonFiles.stderr
    )
  }

  private def configureRunMillProcess(
      cmd: Seq[String],
      daemonDir: os.Path,
      stdin: os.ProcessInput = os.Inherit,
      stdout: os.ProcessOutput = os.Inherit,
      stderr: os.ProcessOutput = os.Inherit
  ): Process = {
    val sandbox = daemonDir / DaemonFiles.sandbox
    os.makeDir.all(sandbox)

    val env = Map(
      EnvVars.MILL_WORKSPACE_ROOT -> os.pwd.toString,
      EnvVars.MILL_ENABLE_STATIC_CHECKS -> "true"
    ) ++ (
      if (sys.env.contains(EnvVars.MILL_EXECUTABLE_PATH)) Map.empty
      else Map(EnvVars.MILL_EXECUTABLE_PATH -> getExecutablePath)
    ) ++ {
      val jdkJavaOptions = sys.env.getOrElse("JDK_JAVA_OPTIONS", "")
      val javaOpts = sys.env.getOrElse("JAVA_OPTS", "")
      val opts = s"$jdkJavaOptions $javaOpts".trim
      if (opts.nonEmpty) Map("JDK_JAVA_OPTIONS" -> opts) else Map.empty
    }

    // destroyOnExit = false to prevent the daemon from being killed when the Mill client exits.
    // The daemon is a long-lived background process that should survive client disconnections.
    os.proc(cmd).spawn(
      cwd = sandbox,
      env = env,
      stdin = stdin,
      stdout = stdout,
      stderr = stderr,
      destroyOnExit = false
    ).wrapped
  }

  def loadMillConfig(key: String): Seq[String] = {
    val configFile = os.pwd / s".$key"
    val workspaceDir = os.pwd.toString

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
        .map(name => os.pwd / name)
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
        System.err.println(s"Failed parsing build header: ${f.error}")
        System.exit(1)
        Seq.empty
    }
  }

  def millServerTimeout: Option[String] =
    sys.env.get(EnvVars.MILL_SERVER_TIMEOUT_MILLIS)

  def isWin: Boolean = System.getProperty("os.name", "").startsWith("Windows")

  def javaHome(): Option[os.Path] = {
    val jvmVersion = loadMillConfig(ConfigConstants.millJvmVersion)
      .headOption
      .getOrElse(BuildInfo.defaultJvmVersion)

    val jvmIndexVersion = loadMillConfig(ConfigConstants.millJvmIndexVersion).headOption

    // Handle "system" specially - return None to use PATH-based Java lookup
    // (javaExe returns "java" when javaHome is None, using PATH lookup)
    if (jvmVersion == "system") None
    else if (jvmVersion != null) Some(CoursierClient.resolveJavaHome(jvmVersion, jvmIndexVersion))
    else None
  }

  def javaExe(): String = {
    javaHome() match {
      case None => "java"
      case Some(home) =>
        val exeName = if (isWin) "java.exe" else "java"
        (home / "bin" / exeName).toString
    }
  }

  def millLaunchJvmCommand(runnerClasspath: Seq[os.Path]): Seq[String] = {
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

    Seq(javaExe()) ++
      millProps ++
      serverTimeoutOpt ++
      encodingOpts ++
      loadMillConfig(ConfigConstants.millJvmOpts) ++
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
