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
      runnerClasspath: Seq[String],
      mainClass: String,
      useFileLocks: Boolean
  ): Int = {
    val sig = f"${UUID.randomUUID().hashCode}%08x"
    val processDir = os.pwd / OutFiles.OutFiles.outFor(outMode) / OutFiles.OutFiles.millNoDaemon / sig

    prepareMillRunFolder(processDir)

    val userPropsSeq = ClientUtil.getUserSetProperties().map { case (k, v) => s"-D$k=$v" }.toSeq

    val cmd = millLaunchJvmCommand(outMode, runnerClasspath) ++
      userPropsSeq ++
      Seq(mainClass, processDir.toString, outMode.asString, useFileLocks.toString) ++
      millOpts(outMode) ++
      args

    var interrupted = false
    try {
      val p = configureRunMillProcess(cmd, processDir)
      p.waitFor()
    } catch {
      case e: InterruptedException =>
        interrupted = true
        throw e
    } finally {
      if (!interrupted && os.exists(processDir)) {
        os.remove.all(processDir)
      }
    }
  }

  def launchMillDaemon(
      daemonDir: os.Path,
      outMode: OutFolderMode,
      runnerClasspath: Seq[String],
      useFileLocks: Boolean
  ): Process = {
    val cmd = millLaunchJvmCommand(outMode, runnerClasspath) ++
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

    os.proc(cmd).spawn(cwd = sandbox, env = env, stdout = stdout, stderr = stderr).wrapped
  }

  def loadMillConfig(outMode: OutFolderMode, key: String): Seq[String] = {
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
          cachedComputedValue(
            outMode,
            key,
            headerData,
            () => parseConfigFromHeader(headerData, key, env)
          )
        }
        .getOrElse(Seq.empty)
    }
  }

  private def parseConfigFromHeader(headerData: String, key: String, env: Map[String, String]): Seq[String] = {
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

  def millJvmOpts(outMode: OutFolderMode): Seq[String] =
    loadMillConfig(outMode, ConfigConstants.millJvmOpts)

  def millOpts(outMode: OutFolderMode): Seq[String] =
    loadMillConfig(outMode, ConfigConstants.millOpts)

  def millJvmVersion(outMode: OutFolderMode): Option[String] =
    loadMillConfig(outMode, ConfigConstants.millJvmVersion).headOption

  def millJvmIndexVersion(outMode: OutFolderMode): Option[String] =
    loadMillConfig(outMode, ConfigConstants.millJvmIndexVersion).headOption

  def millServerTimeout: Option[String] =
    sys.env.get(EnvVars.MILL_SERVER_TIMEOUT_MILLIS)

  def isWin: Boolean = System.getProperty("os.name", "").startsWith("Windows")

  def javaHome(outMode: OutFolderMode): String = {
    val jvmVersion = millJvmVersion(outMode).getOrElse(BuildInfo.defaultJvmVersion)
    val jvmIndexVersion = millJvmIndexVersion(outMode)

    // Handle "system" specially - return null to use PATH-based Java lookup
    // (javaExe returns "java" when javaHome is null, using PATH lookup)
    if (jvmVersion == "system") {
      null
    } else if (jvmVersion != null) {
      // Include JVM index version in the cache key to invalidate cache when index version changes
      val cacheKey = jvmIndexVersion.map(v => s"$jvmVersion:$v").getOrElse(jvmVersion)
      cachedComputedValue0(
        outMode,
        "java-home",
        cacheKey,
        () => Seq(CoursierClient.resolveJavaHome(jvmVersion, jvmIndexVersion.orNull).getAbsolutePath),
        // Make sure we check to see if the saved java home exists before using
        // it, since it may have been since uninstalled, or the `out/` folder
        // may have been transferred to a different machine
        value => os.exists(os.Path(value.head))
      ).head
    } else null
  }

  def javaExe(outMode: OutFolderMode): String = {
    Option(javaHome(outMode)) match {
      case None => "java"
      case Some(home) =>
        val exeName = if (isWin) "java.exe" else "java"
        (os.Path(home) / "bin" / exeName).toString
    }
  }

  def millLaunchJvmCommand(outMode: OutFolderMode, runnerClasspath: Seq[String]): Seq[String] = {
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

    Seq(javaExe(outMode)) ++
      millProps ++
      serverTimeoutOpt ++
      encodingOpts ++
      millJvmOpts(outMode) ++
      Seq("-cp", runnerClasspath.mkString(File.pathSeparator))
  }

  private case class CachedValue(key: String, value: Seq[String])
  private object CachedValue {
    implicit val rw: upickle.default.ReadWriter[CachedValue] = upickle.default.macroRW
  }

  def cachedComputedValue(
      outMode: OutFolderMode,
      name: String,
      key: String,
      block: () => Seq[String]
  ): Seq[String] = {
    cachedComputedValue0(outMode, name, key, block, _ => true)
  }

  def cachedComputedValue0(
      outMode: OutFolderMode,
      name: String,
      key: String,
      block: () => Seq[String],
      validate: Seq[String] => Boolean
  ): Seq[String] = {
    val cacheFile = os.pwd / OutFiles.OutFiles.outFor(outMode) / "mill-launcher" / name

    val cachedValue: Option[Seq[String]] =
      if (os.exists(cacheFile)) {
        try {
          val cached = upickle.default.read[CachedValue](os.read(cacheFile))
          if (cached.key == key && validate(cached.value)) Some(cached.value)
          else None
        } catch {
          case _: Exception => None // Ignore malformed cache files
        }
      } else None

    cachedValue.getOrElse {
      val value = block()
      os.makeDir.all(cacheFile / os.up)
      os.write.over(cacheFile, upickle.default.write(CachedValue(key, value)))
      value
    }
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
