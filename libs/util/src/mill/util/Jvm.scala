package mill.util

import com.lihaoyi.unroll
import coursier.cache.{ArchiveCache, CachePolicy, FileCache}
import coursier.core.{BomDependency, VariantSelector}
import coursier.error.FetchError.DownloadingArtifacts
import coursier.error.ResolutionError.CantDownloadModule
import coursier.jvm.{JavaHome, JvmCache, JvmChannel, JvmIndex}
import coursier.maven.{MavenRepository, MavenRepositoryLike}
import coursier.params.ResolutionParams
import coursier.parse.RepositoryParser
import coursier.util.Task
import coursier.{Artifacts, Classifier, Dependency, Repository, Resolution, Resolve, Type}
import mill.api.*

import java.io.{BufferedOutputStream, File}
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.jar.{JarEntry, JarOutputStream}
import scala.collection.mutable
import scala.util.Properties.isWin
import scala.util.chaining.scalaUtilChainingOps

/**
 * Utilities related to the JVM platform: JVM processes, classloaders, java executables,
 * launcher scripts, etc.
 */
object Jvm {

  /**
   * Runs a JVM subprocess with the given configuration and returns a
   * [[os.CommandResult]] with it's aggregated output and error streams.
   *
   * @param mainClass The main class to run
   * @param mainArgs Args passed to the `mainClass` main method
   * @param javaHome Optional Java Home override
   * @param jvmArgs Arguments given to the forked JVM
   * @param classPath The classpath
   * @param cpPassingJarPath When `None`, the `-cp` parameter is used to pass the classpath
   *                        to the forked JVM.
   *                        When `Some`, a temporary empty JAR is created
   *                        which contains a `Class-Path` manifest entry containing the actual classpath.
   *                        This might help with long classpaths on OS'es (like Windows)
   *                        which only supports limited command-line length
   * @param env Environment variables used when starting the forked JVM
   * @param propagateEnv If `true` then the current process' environment variables are propagated to subprocess
   * @param cwd The working directory to be used by the forked JVM
   * @param stdin Standard input
   * @param stdout Standard output
   * @param stderr Standard error
   * @param mergeErrIntoOut If `true` then the error output is merged into standard output
   * @param timeout how long to wait in milliseconds for the subprocess to complete (-1 for no timeout)
   * @param shutdownGracePeriod if the timeout is enabled, how long in milliseconds for the subprocess
   *                            to gracefully terminate before attempting to forcibly kill it
   *                            (-1 for no kill, 0 for always kill immediately)
   * @param destroyOnExit Destroy on JVM exit
   * @param check if `true`, an exception will be thrown if process exits with a non-zero exit code
   */
  def callProcess(
      mainClass: String,
      mainArgs: os.Shellable = Seq.empty,
      javaHome: Option[os.Path] = None,
      jvmArgs: os.Shellable = Seq.empty,
      classPath: Iterable[os.Path],
      cpPassingJarPath: Option[os.Path] = None,
      env: Map[String, String] = Map.empty,
      propagateEnv: Boolean = true,
      cwd: os.Path = null,
      stdin: os.ProcessInput = os.Pipe,
      stdout: os.ProcessOutput = os.Pipe,
      stderr: os.ProcessOutput = os.Inherit,
      mergeErrIntoOut: Boolean = false,
      timeout: Long = -1,
      shutdownGracePeriod: Long = 100,
      destroyOnExit: Boolean = true,
      check: Boolean = true
  )(implicit ctx: TaskCtx): os.CommandResult = {
    val cp = cpPassingJarPath match {
      case Some(passingJarPath) if classPath.nonEmpty =>
        createClasspathPassingJar(passingJarPath, classPath.toSeq)
        Seq(passingJarPath)
      case _ => classPath
    }

    val commandArgs = Vector(javaExe(javaHome)) ++
      jvmArgs.value ++
      Option.when(cp.nonEmpty)(Vector(
        "-cp",
        cp.mkString(java.io.File.pathSeparator)
      )).getOrElse(Vector.empty) ++
      Vector(mainClass) ++
      mainArgs.value

    if (cwd != null) os.makeDir.all(cwd)

    ctx.log.debug(
      s"Running ${commandArgs.map(arg => "'" + arg.replace("'", "'\"'\"'") + "'").mkString(" ")}"
    )

    val processResult = os.proc(commandArgs)
      .call(
        cwd = cwd,
        env = env,
        propagateEnv = propagateEnv,
        stdin = stdin,
        stdout = stdout,
        stderr = stderr,
        mergeErrIntoOut = mergeErrIntoOut,
        timeout = timeout,
        shutdownGracePeriod = shutdownGracePeriod,
        destroyOnExit = destroyOnExit,
        check = check
      )
    processResult
  }

  /**
   * Runs a JVM subprocess with the given configuration and streams
   * it's stdout and stderr to the console.
   *
   * @param mainClass The main class to run
   * @param mainArgs Args passed to the `mainClass` main method
   * @param javaHome Optional Java Home override
   * @param jvmArgs Arguments given to the forked JVM
   * @param classPath The classpath
   * @param cpPassingJarPath When `None`, the `-cp` parameter is used to pass the classpath
   *                        to the forked JVM.
   *                        When `Some`, a temporary empty JAR is created
   *                        which contains a `Class-Path` manifest entry containing the actual classpath.
   *                        This might help with long classpaths on OS'es (like Windows)
   *                        which only supports limited command-line length
   * @param env Environment variables used when starting the forked JVM
   * @param propagateEnv If `true` then the current process' environment variables are propagated to subprocess
   * @param cwd The working directory to be used by the forked JVM
   * @param stdin Standard input override
   * @param stdout Standard output override
   * @param stderr Standard error override
   * @param mergeErrIntoOut If `true` then the error output is merged into standard output
   * @param shutdownGracePeriod if the timeout is enabled, how long in milliseconds for the subprocess
   *                            to gracefully terminate before attempting to forcibly kill it
   *                            (-1 for no kill, 0 for always kill immediately)
   * @param destroyOnExit Destroy on JVM exit
   */
  def spawnProcess(
      mainClass: String,
      mainArgs: os.Shellable = Seq.empty,
      javaHome: Option[os.Path] = None,
      jvmArgs: os.Shellable = Seq.empty,
      classPath: Iterable[os.Path],
      cpPassingJarPath: Option[os.Path] = None,
      env: Map[String, String] = Map.empty,
      propagateEnv: Boolean = true,
      cwd: os.Path = null,
      stdin: os.ProcessInput = os.Pipe,
      stdout: os.ProcessOutput = os.Pipe,
      stderr: os.ProcessOutput = os.Inherit,
      mergeErrIntoOut: Boolean = false,
      shutdownGracePeriod: Long = 100,
      destroyOnExit: Boolean = true
  ): os.SubProcess = {
    val cp = cpPassingJarPath match {
      case Some(passingJarPath) if classPath.nonEmpty =>
        createClasspathPassingJar(passingJarPath, classPath.toSeq)
        Seq(passingJarPath)
      case _ => classPath
    }

    val commandArgs = Vector(javaExe(javaHome)) ++
      jvmArgs.value ++
      Option.when(cp.nonEmpty)(
        Vector("-cp", cp.mkString(java.io.File.pathSeparator))
      ).getOrElse(Vector.empty) ++
      Vector(mainClass) ++
      mainArgs.value

    if (cwd != null) os.makeDir.all(cwd)

    val process = os.proc(commandArgs).spawn(
      cwd = cwd,
      env = env,
      stdin = stdin,
      stdout = stdout,
      stderr = stderr,
      mergeErrIntoOut = mergeErrIntoOut,
      propagateEnv = propagateEnv,
      shutdownGracePeriod = shutdownGracePeriod,
      destroyOnExit = destroyOnExit
    )
    process
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

  /**
   * Creates a `java.net.URLClassLoader` with specified parameters
   * @param classPath URLs from which to load classes and resources
   * @param parent parent class loader for delegation
   * @param sharedLoader loader used for shared classes
   * @param sharedPrefixes package prefix for classes that will be loaded by the `sharedLoader`
   * @return new classloader
   */
  def createClassLoader(
      classPath: Iterable[os.Path],
      parent: ClassLoader = null,
      sharedLoader: ClassLoader = getClass.getClassLoader,
      sharedPrefixes: Iterable[String] = Seq(),
      label: String = null
  )(implicit e: sourcecode.Enclosing): MillURLClassLoader = MillURLClassLoader(
    classPath.map(_.toNIO),
    parent,
    sharedLoader,
    sharedPrefixes,
    Option(label).getOrElse(e.value)
  )

  /**
   * @param classPath URLs from which to load classes and resources
   * @param parent parent class loader for delegation
   * @param sharedPrefixes package prefix for classes that will be loaded by the shared loader
   * @param f function that will be called with newly created classloader
   * @tparam T the return type of this function
   * @return return value of the function `f`
   */
  def withClassLoader[T](
      classPath: Iterable[os.Path],
      parent: ClassLoader = null,
      sharedLoader: ClassLoader = getClass.getClassLoader,
      sharedPrefixes: Seq[String] = Seq.empty
  )(f: ClassLoader => T): T = {
    val oldClassloader = Thread.currentThread().getContextClassLoader
    val newClassloader = createClassLoader(
      classPath = classPath,
      parent = parent,
      sharedLoader = sharedLoader,
      sharedPrefixes = sharedPrefixes
    )
    Thread.currentThread().setContextClassLoader(newClassloader)
    try {
      f(newClassloader)
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassloader)
      newClassloader.close()
    }
  }

  def createManifest(mainClass: Option[String]): JarManifest = {
    mainClass.foldLeft(JarManifest.MillDefault)((m, c) =>
      m.add((java.util.jar.Attributes.Name.MAIN_CLASS.toString, c))
    )
  }

  /**
   * Create a JAR file with default inflation level.
   *
   * @param jar The final JAR file
   * @param inputPaths The input paths resembling the content of the JAR file.
   *     Files will be directly included in the root of the archive,
   *     whereas for directories their content is added to the root of the archive.
   * @param manifest The JAR Manifest
   * @param fileFilter A filter to support exclusions of selected files
   * @param includeDirs If `true` the JAR archive will contain directory entries.
   *                    According to the ZIP specification, directory entries are not required.
   *                    In the Java ecosystem, most JARs have directory entries, so including them may reduce compatibility issues.
   *                    Directory entry names will result with a trailing `/`.
   * @param timestamp If specified, this timestamp is used as modification timestamp (mtime) for all entries in the JAR file.
   *                  Having a stable timestamp may result in reproducible files, if all other content, including the JAR Manifest, keep stable.
   */
  def createJar(
      jar: os.Path,
      inputPaths: Seq[os.Path],
      manifest: JarManifest = JarManifest.Empty,
      fileFilter: (os.Path, os.RelPath) => Boolean = (_, _) => true,
      includeDirs: Boolean = true,
      timestamp: Option[Long] = None
  ): os.Path = {

    val curTime = timestamp.getOrElse(System.currentTimeMillis())

    def mTime(file: os.Path) = timestamp.getOrElse(os.mtime(file))

    os.makeDir.all(jar / os.up)
    os.remove.all(jar)

    val seen = mutable.Set.empty[os.RelPath]
    val _ = seen.add(os.sub / "META-INF/MANIFEST.MF")

    val jarStream = new JarOutputStream(
      new BufferedOutputStream(Files.newOutputStream(jar.toNIO)),
      manifest.build
    )

    try {
      assert(inputPaths.iterator.forall(os.exists(_)))

      if (includeDirs) {
        val _ = seen.add(os.sub / "META-INF")
        val entry = new JarEntry("META-INF/")
        entry.setTime(curTime)
        jarStream.putNextEntry(entry)
        jarStream.closeEntry()
      }

      // Note: we only sort each input path, but not the whole archive
      for {
        p <- inputPaths
        (file, mapping) <-
          if (os.isFile(p)) Seq((p, os.sub / p.last))
          else os.walk(p).map(sub => (sub, sub.subRelativeTo(p))).sorted
        if (includeDirs || os.isFile(file)) && !seen(mapping) && fileFilter(p, mapping)
      } {
        val _ = seen.add(mapping)
        val name = mapping.toString() + (if (os.isDir(file)) "/" else "")
        val entry = new JarEntry(name)
        entry.setTime(mTime(file))
        jarStream.putNextEntry(entry)
        if (os.isFile(file)) jarStream.write(os.read.bytes(file))
        jarStream.closeEntry()
      }
      jar
    } finally {
      jarStream.close()
    }
  }

  def createClasspathPassingJar(jar: os.Path, classpath: Seq[os.Path]): Unit = {
    createJar(
      jar = jar,
      inputPaths = Seq(),
      manifest = JarManifest.MillDefault.add(
        "Class-Path" -> classpath.iterator.map(_.toURL.toExternalForm()).mkString(
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
      shellClassPath: Seq[String],
      cmdClassPath: Seq[String],
      jvmArgs: os.Shellable,
      shebang: Boolean = false,
      shellJvmArgs: os.Shellable = Nil,
      cmdJvmArgs: os.Shellable = Nil
  ): String = {

    universalScript(
      shellCommands = {
        val jvmArgsStr = (jvmArgs.value ++ shellJvmArgs.value).mkString(" ")
        val classpathStr = shellClassPath.mkString(":")

        s"""if [ -z "$$JAVA_HOME" ] ; then
           |  JAVACMD="java"
           |else
           |  JAVACMD="$$JAVA_HOME/bin/java"
           |fi
           |
           |exec "$$JAVACMD" $jvmArgsStr $$JAVA_OPTS -cp "$classpathStr" $mainClass "$$@"
           |""".stripMargin
      },
      cmdCommands = {
        val jvmArgsStr = (jvmArgs.value ++ cmdJvmArgs.value).mkString(" ")
        val classpathStr = cmdClassPath.mkString(";")
        s"""setlocal EnableDelayedExpansion
           |set "JAVACMD=java.exe"
           |if not "%JAVA_HOME%"=="" set "JAVACMD=%JAVA_HOME%\\bin\\java.exe"
           |
           |"%JAVACMD%" $jvmArgsStr %JAVA_OPTS% -cp "$classpathStr" $mainClass %*
           |
           |endlocal
           |""".stripMargin
      },
      shebang = shebang
    )
  }

  def createLauncher(
      mainClass: String,
      classPath: Seq[os.Path],
      jvmArgs: os.Shellable,
      destFolder: os.Path
  ): PathRef = {
    val isWin = scala.util.Properties.isWin
    val isBatch =
      isWin && !(org.jline.utils.OSUtils.IS_CYGWIN || org.jline.utils.OSUtils.IS_MSYSTEM)
    val outputPath = destFolder / (if (isBatch) "run.bat" else "run")
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

  private def coursierCache(
      ctx: Option[mill.api.TaskCtx],
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]],
      config: CoursierConfig
  ) =
    FileCache[Task](os.Path(config.cacheLocation).toIO)
      .withCredentials(config.credentials)
      .withTtl(config.ttl)
      .withCachePolicies(config.cachePolicies)
      .pipe { cache =>
        coursierCacheCustomizer.fold(cache)(c => c.apply(cache))
      }
      .pipe { cache =>
        ctx.fold(cache)(c => cache.withLogger(new CoursierTickerResolutionLogger(c)))
      }
      .pipe { cache =>
        if (ctx.fold(false)(_.offline)) cache.withCachePolicies(Seq(CachePolicy.LocalOnly))
        else cache
      }

  /**
   * Resolve dependencies using Coursier, and return very detailed info about their artifacts.
   */
  def getArtifacts(
      repositories: Seq[Repository],
      deps: IterableOnce[Dependency],
      force: IterableOnce[Dependency] = Nil,
      sources: Boolean = false,
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[Resolution => Resolution] = None,
      ctx: Option[mill.api.TaskCtx] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None,
      artifactTypes: Option[Set[Type]] = None,
      resolutionParams: ResolutionParams = ResolutionParams(),
      checkGradleModules: Boolean = false,
      @unroll config: CoursierConfig = CoursierConfig.default()
  ): Result[coursier.Artifacts.Result] = {
    val resolutionRes = resolveDependenciesMetadataSafe(
      repositories,
      deps,
      force,
      mapDependencies,
      customizer,
      ctx,
      coursierCacheCustomizer,
      resolutionParams,
      checkGradleModules = checkGradleModules,
      config = config
    )

    resolutionRes.flatMap { resolution =>
      val coursierCache0 = coursierCache(ctx, coursierCacheCustomizer, config)

      val artifactsResultOrError = Artifacts(coursierCache0)
        .withResolution(resolution)
        .withClassifiers(
          if (sources) Set(Classifier("sources"))
          else Set.empty
        )
        .withAttributes(
          if (sources) Seq(VariantSelector.AttributesBased.sources)
          else Nil
        )
        .withArtifactTypesOpt(artifactTypes)
        .eitherResult()

      artifactsResultOrError match {
        case Left(error: DownloadingArtifacts) =>
          val errorDetails = error.errors
            .map(_._2)
            .map(e => s"${System.lineSeparator()}  ${e.describe}")
            .mkString
          Result.Failure(
            s"Failed to load ${if (sources) "source " else ""}dependencies" + errorDetails
          )
        case Right(res) =>
          Result.Success(res)
      }
    }
  }

  /**
   * Resolve dependencies using Coursier.
   *
   * We do not bother breaking this out into the separate JvmWorkerApi classpath,
   * because Coursier is already bundled with mill/Ammonite to support the
   * `//| mvnDeps` syntax.
   */
  def resolveDependencies(
      repositories: Seq[Repository],
      deps: IterableOnce[Dependency],
      force: IterableOnce[Dependency],
      sources: Boolean = false,
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[Resolution => Resolution] = None,
      ctx: Option[mill.api.TaskCtx] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None,
      artifactTypes: Option[Set[Type]] = None,
      resolutionParams: ResolutionParams = ResolutionParams(),
      checkGradleModules: Boolean = false,
      @unroll config: CoursierConfig = CoursierConfig.default()
  ): Result[Seq[PathRef]] =
    getArtifacts(
      repositories,
      deps,
      force,
      sources,
      mapDependencies,
      customizer,
      ctx,
      coursierCacheCustomizer,
      artifactTypes,
      resolutionParams,
      checkGradleModules = checkGradleModules,
      config = config
    ).map { res =>
      BuildCtx.withFilesystemCheckerDisabled {
        res.files
          .map(os.Path(_))
          .map(PathRef(_, quick = true))
      }
    }

  def jvmIndex(
      ctx: Option[mill.api.TaskCtx] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None,
      @unroll config: CoursierConfig = CoursierConfig.default()
  ): JvmIndex = {
    val coursierCache0 = coursierCache(ctx, coursierCacheCustomizer, config)
    coursierCache0.logger.use(jvmIndex0(ctx, coursierCacheCustomizer, config = config))
      .unsafeRun()(using coursierCache0.ec)
  }

  def jvmIndex0(
      ctx: Option[mill.api.TaskCtx] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None,
      jvmIndexVersion: String = "latest.release",
      @unroll config: CoursierConfig = CoursierConfig.default()
  ): Task[JvmIndex] = {
    val coursierCache0 = coursierCache(ctx, coursierCacheCustomizer, config)
    JvmIndex.load(
      cache = coursierCache0, // the coursier.cache.Cache instance to use
      repositories = config.repositories, // repositories to use
      indexChannel = JvmChannel.module(
        JvmChannel.centralModule(),
        version = jvmIndexVersion
      ) // use new indices published to Maven Central
    )
  }

  /**
   * Resolve java home using Coursier.
   *
   * The id string has format "$DISTRIBUTION:$VERSION". e.g. graalvm-community:23.0.0
   */
  def resolveJavaHome(
      id: String,
      ctx: Option[mill.api.TaskCtx] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None,
      jvmIndexVersion: String = mill.api.BuildInfo.coursierJvmIndexVersion,
      useShortPaths: Boolean = false,
      @unroll config: CoursierConfig = CoursierConfig.default()
  ): Result[os.Path] = {
    val coursierCache0 = coursierCache(ctx, coursierCacheCustomizer, config)
    val shortPathDirOpt = Option.when(useShortPaths) {
      if (isWin)
        // On Windows, prefer to use System.getenv over sys.env (or ctx.env for
        // now), as the former respects the case-insensitiveness of env vars on
        // Windows, while the latter doesn't
        os.Path(System.getenv("UserProfile")) / ".mill/cache/jvm"
      else {
        val cacheBase = ctx.map(_.env)
          .getOrElse(sys.env)
          .get("XDG_CACHE_HOME")
          .map(os.Path(_))
          .getOrElse(os.home / ".cache")
        cacheBase / "mill/jvm"
      }
    }
    val jvmCache = JvmCache()
      .withArchiveCache(
        ArchiveCache()
          .withLocation(os.Path(config.archiveCacheLocation).toIO)
          .withCache(coursierCache0)
          .withShortPathDirectory(shortPathDirOpt.map(_.toIO))
      )
      .withIndex(jvmIndex0(
        ctx,
        coursierCacheCustomizer,
        jvmIndexVersion,
        config = config
      ))
    val javaHome = JavaHome()
      .withCache(jvmCache)
      // when given a version like "17", always pick highest version in the index
      // rather than the highest already on disk
      .withUpdate(true)
    val file = coursierCache0.logger.use(javaHome.get(id))
      .unsafeRun()(using coursierCache0.ec)
    Result.Success(os.Path(file))

  }

  def resolveDependenciesMetadataSafe(
      repositories: Seq[Repository],
      deps: IterableOnce[Dependency],
      force: IterableOnce[Dependency],
      mapDependencies: Option[Dependency => Dependency] = None,
      customizer: Option[Resolution => Resolution] = None,
      ctx: Option[mill.api.TaskCtx] = None,
      coursierCacheCustomizer: Option[FileCache[Task] => FileCache[Task]] = None,
      resolutionParams: ResolutionParams = ResolutionParams(),
      boms: IterableOnce[BomDependency] = Nil,
      checkGradleModules: Boolean = false,
      @unroll config: CoursierConfig = CoursierConfig.default()
  ): Result[Resolution] = {

    val rootDeps = deps.iterator
      .map(d => mapDependencies.fold(d)(_.apply(d)))
      .toSeq

    val forceVersions = force.iterator
      .map(mapDependencies.getOrElse(identity[Dependency](_)))
      .map { d => d.module -> d.version }
      .toMap

    val offlineMode = ctx.fold(false)(_.offline)
    val coursierCache0 = coursierCache(ctx, coursierCacheCustomizer, config)

    val resolutionParams0 = resolutionParams.addForceVersion(forceVersions.toSeq*)

    val repositories0 =
      if (checkGradleModules)
        repositories.map {
          case m: MavenRepositoryLike.WithModuleSupport =>
            m.withCheckModule(true)
          case other => other
        }
      else
        repositories

    def testOverridesReposFor(envVar: String) =
      Option(System.getenv(envVar))
        .toSeq
        .flatMap(_.split(File.pathSeparator).toSeq)
        .filter(_.nonEmpty)
        .map { path =>
          MavenRepository(os.Path(path).toURI.toASCIIString)
            .withCheckModule(checkGradleModules)
        }

    val testOverridesRepos =
      testOverridesReposFor("MILL_LOCAL_TEST_REPO") ++
        testOverridesReposFor("MILL_USER_TEST_REPO")

    val resolve = Resolve()
      .withCache(coursierCache0)
      .withDependencies(rootDeps)
      .withRepositories(testOverridesRepos ++ repositories0)
      .withResolutionParams(resolutionParams0)
      .withMapDependenciesOpt(mapDependencies)
      .withBoms(boms.iterator.toSeq)
      .withConfFiles(config.confFiles.map(_.toNIO))
      .withMirrors(config.mirrors)

    resolve.either() match {
      case Left(error) =>
        val cantDownloadErrors = error.errors.collect {
          case cantDownload: CantDownloadModule => cantDownload
        }
        if (error.errors.length == cantDownloadErrors.length) {
          val extraHeader =
            if (offlineMode)
              """
                |*** Mill is in offline mode (--offline) ***
                |It can not download new dependencies from remote repositories.
                |You may need to run Mill without the `--offline` option at least once
                |to download required remote dependencies.
                |Run `mill __.prepareOffline` to fetch most remote resources at once.
                |
                |""".stripMargin
            else ""

          val header =
            s"""|
                |${extraHeader}Resolution failed for ${cantDownloadErrors.length} modules:
                |--------------------------------------------
                |""".stripMargin

          val helpMessage =
            s"""|
                |--------------------------------------------
                |
                |For additional information on library dependencies, see the docs at
                |${mill.api.BuildInfo.millDocUrl}/mill/Library_Dependencies.html""".stripMargin

          val errLines = cantDownloadErrors
            .map { err =>
              s"  ${err.module.trim}:${err.versionConstraint.asString} \n\t" +
                err.perRepositoryErrors.mkString("\n\t")
            }
            .mkString("\n")
          val msg = header + errLines + "\n" + helpMessage + "\n"
          Result.Failure(msg)
        } else {
          throw error
        }
      case Right(resolution0) =>
        val resolution = customizer.fold(resolution0)(_.apply(resolution0))
        Result.Success(resolution)
    }
  }

  // Parse a list of repositories from their string representation
  private[mill] def reposFromStrings(repoList: Seq[String]): Result[Seq[Repository]] = {
    RepositoryParser.repositories(repoList).either match {
      case Left(errs) =>
        val msg =
          s"Invalid repository string:" + System.lineSeparator() +
            errs.map("  " + _ + System.lineSeparator()).mkString
        Result.Failure(msg)
      case Right(repos) =>
        Result.Success(repos)
    }
  }
}
