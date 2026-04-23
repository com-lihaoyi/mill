package mill.daemon

import mill.api.daemon.internal.{
  BuildFileApi,
  CompileProblemReporter,
  EvaluatorApi,
  PathRefApi,
  RootModuleApi,
  TaskApi
}
import mill.api.{BuildCtx, Logger, PathRef, Result, SelectMode, SystemStreams, Val}
import mill.constants.CodeGenConstants.*
import mill.constants.OutFiles.OutFiles.{millBuild, millRunnerState}
import mill.internal.Util
import mill.api.daemon.Watchable
import mill.api.internal.RootModule
import mill.internal.PrefixLogger
import mill.meta.{BootstrapRootModule, MillBuildRootModule}
import mill.api.daemon.internal.CliImports
import mill.api.daemon.WorkspaceLocking
import mill.meta.DiscoveredBuildFiles.findRootBuildFiles
import mill.server.Server
import mill.util.BuildInfo
import os.Path

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.Using
import scala.collection.mutable.Buffer

/**
 * Logic around bootstrapping Mill, creating a [[MillBuildRootModule.BootstrapModule]]
 * and compiling builds/meta-builds and classloading their [[RootModule]]s so we
 * can evaluate the requested tasks on the [[RootModule]] representing the user's
 * `build.mill` file.
 *
 * When Mill is run in client-server mode, or with `--watch`, then reusable
 * meta-build data is shared across concurrent launchers via the per-daemon
 * [[sharedState]] (which is itself a [[RunnerState]] holding only the
 * "stripped" shared parts of each meta-build frame), and per-launcher state
 * is kept in [[prevCommandState]] so a subsequent watch iteration can pick up
 * fallback classloaders to close on refresh.
 *
 * When a subsequent evaluation happens, each level of [[evaluateRec]] reads
 * the current published meta-build frame under the per-depth read lock and,
 * if reusable, wraps it in a [[RunnerState.MetaBuildFrame]] alongside this
 * launcher's own lease/evaluator. If not reusable, the launcher takes the
 * write lock, refreshes, and publishes a new frame to [[sharedState]].
 */
class MillBuildBootstrap(
    topLevelProjectRoot: os.Path,
    output: os.Path,
    keepGoing: Boolean,
    imports: Seq[String],
    env: Map[String, String],
    ec: Option[ThreadPoolExecutor],
    tasksAndParams: Seq[String],
    prevCommandState: RunnerState,
    logger: Logger,
    requestedMetaLevel: Option[Int],
    allowPositionalCommandArgs: Boolean,
    systemExit: Server.StopServer,
    streams0: SystemStreams,
    selectiveExecution: Boolean,
    offline: Boolean,
    useFileLocks: Boolean,
    workspaceLockManager: WorkspaceLocking.Manager,
    sharedState: AtomicReference[RunnerState],
    reporter: EvaluatorApi => Int => Option[CompileProblemReporter],
    enableTicker: Boolean
) { outer =>
  import MillBuildBootstrap.*

  val millBootClasspath: Seq[os.Path] = prepareMillBootClasspath(output)
  val millBootClasspathPathRefs: Seq[PathRef] = millBootClasspath.map(PathRef(_, quick = true))

  def evaluate(): RunnerState = CliImports.withValue(imports) {
    val runnerState = evaluateRec(0)

    // Meta-build frame logs go to shared canonical paths. Writes are idempotent
    // because the content is deterministic for a given published classloader —
    // concurrent launchers racing on the same path produce the same bytes.
    // Final-frame logs go to the same convention; concurrent launchers overwrite
    // each other's data at the final depth, which is acceptable since the log is
    // only consumed by the writer for debugging and by tests.
    def write(depth: Int, logged: RunnerState.Frame.Logged): Unit =
      os.write.over(
        recOut(output, depth) / millRunnerState,
        upickle.write(logged, indent = 4),
        createFolders = true
      )
    for (frame <- runnerState.metaBuildFrames) write(frame.depth, frame.loggedData)
    for (frame <- runnerState.finalFrame) write(frame.depth, frame.loggedData)

    runnerState
  }

  /** Total depths already processed in `nestedState`: meta-build frames + final frame. */
  private def processedDepths(state: RunnerState): Int =
    state.metaBuildFrames.size + state.finalFrame.size

  def evaluateRec(depth: Int): RunnerState = logger.withChromeProfile(s"meta-level $depth") {
    // println(s"+evaluateRec($depth) " + recRoot(projectRoot, depth))
    val currentRoot = recRoot(topLevelProjectRoot, depth)

    val nestedState =
      if (containsBuildFile(currentRoot)) evaluateRec(depth + 1)
      else makeBootstrapState(currentRoot)

    val nestedDepths = processedDepths(nestedState)
    val requestedDepth = computeRequestedDepth(requestedMetaLevel, depth, nestedDepths)

    // If an earlier frame errored out, just propagate the error to this frame
    if (nestedState.errorOpt.isDefined) nestedState
    else if (depth == 0 && (requestedDepth > nestedDepths || requestedDepth < 0)) {
      // User has requested a frame depth, we actually don't have
      nestedState.withError(invalidLevelMsg(requestedMetaLevel, nestedDepths))
    } else if (nestedState.finalFrame.isDefined) {
      // Final tasks already ran at a deeper level (--meta-level or @nonBootstrapped); nothing to do here.
      nestedState
    } else {
      val rootModuleRes = nestedState.metaBuildFrameAt(depth + 1) match {
        case None => Result.Success(BuildFileApi.Bootstrap(nestedState.bootstrapModuleOpt.get))
        case Some(nestedFrame) => getRootModule(nestedFrame.classLoaderOpt.get)
      }

      rootModuleRes match {
        case f: Result.Failure =>
          nestedState.withError(Util.formatError(f, logger.prompt.errorColor))

        case Result.Success(buildFileApi) =>
          Using.resource(makeEvaluator(nestedState, buildFileApi.rootModule, depth)) { evaluator =>
            // Check if all requested tasks are @nonBootstrapped
            val shouldShortCircuit =
              // When there is an explicit `--meta-level`, use that and ignore any
              // `@nonBootstrapped` annotations
              if (requestedMetaLevel.nonEmpty) Result.Success(false)
              else
                evaluator.areAllNonBootstrapped(
                  tasksAndParams,
                  SelectMode.Separated,
                  allowPositionalCommandArgs
                )

            shouldShortCircuit match {
              case Result.Success(true) =>
                processFinalTasks(nestedState, buildFileApi, evaluator, depth)

              // For both Success(false) and Failure, proceed with normal evaluation.
              // If areAllNonBootstrapped failed (e.g., task doesn't exist), the actual
              // evaluation will also fail, but moduleWatched will be properly captured.
              case _ =>
                if (depth > requestedDepth) {
                  processRunClasspath(nestedState, buildFileApi, evaluator, depth)
                } else if (depth == requestedDepth) {
                  processFinalTasks(nestedState, buildFileApi, evaluator, depth)
                } else ??? // should be handled by outer conditional
            }
          }
      }
    }
  }

  private def makeBootstrapState(currentRoot: os.Path): RunnerState = {
    val (useDummy, foundRootBuildFileName) = findRootBuildFiles(topLevelProjectRoot)

    val (mod, error) = makeBootstrapModule(currentRoot, foundRootBuildFileName, useDummy) match {
      case Result.Success(bootstrapModule) => (Some(bootstrapModule), None)
      case f: Result.Failure => (None, Some(Util.formatError(f, logger.prompt.errorColor)))
    }

    val bootstrapEvalWatched =
      Watchable.Path.from(PathRef(topLevelProjectRoot / foundRootBuildFileName))

    RunnerState(mod, error, Some(foundRootBuildFileName), Seq(bootstrapEvalWatched))
  }

  def makeEvaluator(
      nestedState: RunnerState,
      rootModule: RootModuleApi,
      depth: Int
  ): EvaluatorApi = {
    val currentRoot = recRoot(topLevelProjectRoot, depth)
    val staticBuildOverrides0 = tryReadParent(currentRoot, "build.mill.yaml")
      .orElse(tryReadParent(currentRoot, "build.mill"))

    // The classloader that will load the code at this depth lives on the meta-build
    // frame one level deeper (depth + 1). If there is no such frame, we're loading
    // from bootstrap and have no prior classloader to reference.
    val nestedFrame = nestedState.metaBuildFrameAt(depth + 1)

    val staticBuildOverrideFiles =
      staticBuildOverrides0.toSeq ++ nestedFrame.fold(Map.empty)(_.buildOverrideFiles)

    val millClassloaderIdentityHash0 = nestedFrame
      .flatMap(_.classLoaderOpt)
      .map(_.hashCode())
      .getOrElse(0)

    // Use the process-level shared worker cache. Workers are thread-safe and
    // can be shared across concurrent commands. SharedWorkerCache.forDepth
    // handles classloader changes automatically (closes stale workers).
    val workerCache =
      SharedWorkerCache.forDepth(topLevelProjectRoot, depth, millClassloaderIdentityHash0)

    makeEvaluator0(
      projectRoot = topLevelProjectRoot,
      output = output,
      keepGoing = keepGoing,
      env = env,
      logger = logger,
      ec = ec,
      allowPositionalCommandArgs = allowPositionalCommandArgs,
      systemExit = systemExit,
      streams0 = streams0,
      selectiveExecution = selectiveExecution,
      offline = offline,
      useFileLocks = useFileLocks,
      workspaceLockManager = workspaceLockManager,
      workerCache = workerCache,
      codeSignatures = nestedFrame.map(_.codeSignatures).getOrElse(Map.empty),
      // Pass spanning tree from the frame - only populated when classloader changed
      spanningInvalidationTree = nestedFrame.flatMap(_.spanningInvalidationTree),
      rootModule = rootModule,
      // Use the current frame's runClasspath (includes mvnDeps and Mill jars) but filter out
      // compile.dest and generatedScriptSources.dest since build code changes are handled
      // by codesig analysis, not by classLoaderSigHash.
      millClassloaderSigHash = nestedFrame match {
        case Some(frame) =>
          val compileDestPath = frame.compileOutput.map(p => os.Path(p.javaPath))
          frame.runClasspath
            .filter { p =>
              val path = os.Path(p.javaPath)
              !compileDestPath.contains(path) &&
              !path.toString.contains("generatedScriptSources.dest")
            }
            .map(p => (os.Path(p.javaPath), p.sig))
            .hashCode()
        case None =>
          millBootClasspathPathRefs
            .map(p => (os.Path(p.javaPath), p.sig))
            .hashCode()
      },
      millClassloaderIdentityHash = millClassloaderIdentityHash0,
      depth = depth,
      actualBuildFileName = nestedState.buildFile,
      enableTicker = enableTicker,
      staticBuildOverrideFiles = staticBuildOverrideFiles.toMap
    )
  }

  private def makeBootstrapModule(
      currentRoot: Path,
      foundRootBuildFileName: String,
      useDummy: Boolean
  ) = {
    mill.api.ExecResult.catchWrapException {
      given rootModuleInfo: RootModule.Info =
        new RootModule.Info(currentRoot, output, topLevelProjectRoot)
      if (useDummy) new BootstrapRootModule.Instance()
      else new MillBuildRootModule.BootstrapModule(currentRoot / foundRootBuildFileName)
    }
  }

  /**
   * Handles the compilation of `build.mill` or one of the meta-builds. These
   * cases all only need us to run evaluate `runClasspath` and
   * `scriptImportGraph` to instantiate their classloader/`RootModule` to feed
   * into the next level's [[Evaluator]].
   *
   * Note that if the `runClasspath` doesn't change, we re-use the previous
   * classloader, saving us from having to re-instantiate it and for the code
   * inside to be re-JITed
   */
  def processRunClasspath(
      nestedState: RunnerState,
      buildFileApi: BuildFileApi,
      evaluator: EvaluatorApi,
      depth: Int
  ): RunnerState = {
    evaluateWithWatches(
      buildFileApi,
      evaluator,
      Seq("millBuildRootModuleResult"),
      selectiveExecution = false,
      reporter = reporter(evaluator)
    ) match {
      case (f: Result.Failure, evalWatches, moduleWatches) =>
        nestedState
          .withMetaBuildFrame(
            RunnerState.MetaBuildFrame.failed(depth, evaluator, evalWatches, moduleWatches)
          )
          .withError(mill.internal.Util.formatError(f, logger.prompt.errorColor))

      case (
            Result.Success(Seq(Tuple5(
              runClasspath: Seq[PathRefApi],
              compileClasses: PathRefApi,
              codeSignatures: Map[String, Int],
              buildOverrideFiles: Map[java.nio.file.Path, String],
              spanningInvalidationTree: String
            ))),
            evalWatches,
            moduleWatches
          ) =>
        def createClassLoader() = {
          // Write root module info as a classpath resource so BuildFileCls can
          // read it from the classloader without needing mutable global state.
          // This classloader will be used at depth-1 to load the root module.
          val rootModuleInfoDir = recOut(output, depth) / "rootModuleInfo.dest"
          os.write.over(
            rootModuleInfoDir / "mill" / "rootModuleInfo.json",
            ujson.Obj(
              "projectRoot" -> recRoot(topLevelProjectRoot, depth - 1).toString,
              "output" -> output.toString,
              "topLevelProjectRoot" -> topLevelProjectRoot.toString
            ).render(indent = 2),
            createFolders = true
          )

          mill.util.Jvm.createClassLoader(
            runClasspath.map(p => os.Path(p.javaPath)) :+ rootModuleInfoDir,
            null,
            sharedLoader = classOf[MillBuildBootstrap].getClassLoader,
            sharedPrefixes = Seq("java.", "javax.", "scala.", "mill.api.daemon", "sbt.testing.")
          )
        }

        // The outer frame's moduleWatched tracks files watched by tasks running at depth-1
        // under this classloader; consulting it signals whether anything watched changed
        // since the previous run, which in turn is a reason to re-create the classloader.
        // For the shallowest meta-build (depth=1), the outer level is the final depth and
        // we consult prevCommandState because the final frame is per-launcher and not
        // published into the daemon-wide shared state.
        def outerModuleWatched: Seq[Watchable] =
          prevCommandState.metaBuildFrameAt(depth - 1).map(_.moduleWatched)
            .orElse(prevCommandState.finalFrame.map(_.moduleWatched))
            .getOrElse(Nil)

        def needsClassloaderRefresh(at: Option[RunnerState.MetaBuildFrame]): Boolean = {
          val runClasspathChanged =
            !at.exists(_.runClasspath.map(_.sig).sum == runClasspath.map(_.sig).sum)
          val moduleWatchChanged = outerModuleWatched.exists(w => !Watching.haveNotChanged(w))
          runClasspathChanged || moduleWatchChanged
        }

        def acquireMetaBuildLock(kind: WorkspaceLocking.LockKind) =
          workspaceLockManager.acquireLock(WorkspaceLocking.metaBuildResource(depth, kind))

        def buildReusable(classLoader: mill.api.MillURLClassLoader) = RunnerState.ReusableFrame(
          classLoader = classLoader,
          runClasspath = runClasspath,
          compileOutput = compileClasses,
          codeSignatures = codeSignatures,
          buildOverrideFiles = buildOverrideFiles,
          spanningInvalidationTree = spanningInvalidationTree,
          workerCacheSummary = RunnerState.Frame.summarizeWorkerCache(evaluator.workerCache)
        )

        def launcherFrame(
            reusable: RunnerState.ReusableFrame,
            lease: WorkspaceLocking.ResourceLease
        ) = RunnerState.MetaBuildFrame(
          depth = depth,
          reusable = Some(reusable),
          evalWatched = evalWatches,
          moduleWatched = moduleWatches,
          evaluator = Some(evaluator),
          metaBuildReadLease = Some(lease)
        )

        // Happy path: read lease + reuse the published frame. On miss, drop the read
        // and reacquire as write (the semaphore is not reentrant), re-check under the
        // write, then either reuse or build+publish a fresh frame. Downgrade to a read
        // lease for the returned frame to own.
        val readLease = acquireMetaBuildLock(WorkspaceLocking.LockKind.Read)
        val frame = closeOnThrow(readLease) {
          val published = sharedState.get().metaBuildFrameAt(depth)
          if (published.flatMap(_.reusable).isDefined && !needsClassloaderRefresh(published))
            launcherFrame(published.get.reusable.get, readLease)
          else {
            readLease.close()
            val writeLease = acquireMetaBuildLock(WorkspaceLocking.LockKind.Write)
            closeOnThrow(writeLease) {
              val latest = sharedState.get().metaBuildFrameAt(depth)
              val reusable =
                if (!needsClassloaderRefresh(latest) && latest.flatMap(_.reusable).isDefined)
                  latest.get.reusable.get
                else {
                  // Close any classloaders we're about to replace. Workers at this depth
                  // are handled by SharedWorkerCache.forDepth (called in makeEvaluator).
                  Seq(
                    prevCommandState.metaBuildFrameAt(depth).flatMap(_.classLoaderOpt),
                    latest.flatMap(_.classLoaderOpt)
                  ).flatten.distinct.foreach(_.close())
                  val fresh = buildReusable(createClassLoader())
                  sharedState.updateAndGet(_.withMetaBuildFrame(
                    RunnerState.MetaBuildFrame(
                      depth = depth,
                      reusable = Some(fresh),
                      evalWatched = Nil,
                      moduleWatched = Nil
                    )
                  ))
                  fresh
                }
              writeLease.downgradeToRead()
              launcherFrame(reusable, writeLease)
            }
          }
        }
        nestedState.withMetaBuildFrame(frame)

      case unknown => sys.error(unknown.toString())
    }
  }

  /**
   * Handles the final evaluation of the user-provided tasks. Since there are
   * no further levels to evaluate, we do not need to save a `scriptImportGraph`,
   * classloader, or runClasspath.
   */
  def processFinalTasks(
      nestedState: RunnerState,
      buildFileApi: BuildFileApi,
      evaluator0: EvaluatorApi,
      depth: Int
  ): RunnerState = {
    val evaluator = evaluator0.withIsFinalDepth(true)
    val (evaled, evalWatched, moduleWatched) = evaluateWithWatches(
      buildFileApi = buildFileApi,
      evaluator = evaluator,
      tasksAndParams = tasksAndParams,
      selectiveExecution = selectiveExecution,
      reporter = reporter(evaluator)
    )

    val withFinal = nestedState.withFinalFrame(
      RunnerState.FinalFrame(depth, evaluator, evalWatched, moduleWatched)
    )
    evaled match {
      case f: Result.Failure =>
        withFinal.withError(mill.internal.Util.formatError(f, logger.prompt.errorColor))
      case _ => withFinal
    }
  }

}

object MillBuildBootstrap {

  /** Run `body`; if it throws, best-effort close `resource` (suppressing cleanup failures). */
  private def closeOnThrow[R <: AutoCloseable, A](resource: R)(body: => A): A =
    try body
    catch {
      case e: Throwable =>
        try resource.close()
        catch { case _: Throwable => }
        throw e
    }

  // Keep this outside of `case class MillBuildBootstrap` because otherwise the lambdas
  // tend to capture the entire enclosing instance, causing memory leaks
  def makeEvaluator0(
      projectRoot: os.Path,
      output: os.Path,
      keepGoing: Boolean,
      env: Map[String, String],
      logger: Logger,
      ec: Option[ThreadPoolExecutor],
      allowPositionalCommandArgs: Boolean,
      systemExit: Server.StopServer,
      streams0: SystemStreams,
      selectiveExecution: Boolean,
      offline: Boolean,
      useFileLocks: Boolean,
      workspaceLockManager: WorkspaceLocking.Manager,
      workerCache: collection.mutable.Map[String, (Int, Val, TaskApi[?])],
      codeSignatures: Map[String, Int],
      // JSON string to avoid classloader issues when crossing classloader boundaries
      spanningInvalidationTree: Option[String],
      rootModule: RootModuleApi,
      millClassloaderSigHash: Int,
      millClassloaderIdentityHash: Int,
      depth: Int,
      actualBuildFileName: Option[String] = None,
      enableTicker: Boolean,
      staticBuildOverrideFiles: Map[java.nio.file.Path, String]
  ): EvaluatorApi = {
    val bootLogPrefix: Seq[String] =
      if (depth == 0) Nil
      else Seq(
        (Seq.fill(depth - 1)(millBuild) ++
          Seq(actualBuildFileName.getOrElse("<build>")))
          .mkString("/")
      )

    val outPath = recOut(output, depth)
    val baseLogger = new PrefixLogger(logger, bootLogPrefix)
    val cl = rootModule.getClass.getClassLoader
    val evalImplCls = cl.loadClass("mill.eval.EvaluatorImpl")
    val execCls = cl.loadClass("mill.exec.Execution")

    lazy val evaluator: EvaluatorApi =
      evalImplCls.getConstructors.minBy(_.getParameterCount).newInstance(
        allowPositionalCommandArgs,
        selectiveExecution,
        // Use the shorter convenience constructor not the primary one
        // TODO: Check if named tuples could make this call more typesafe
        execCls.getConstructors.minBy(_.getParameterCount).newInstance(
          baseLogger,
          projectRoot.toNIO,
          outPath.toNIO,
          outPath.toNIO,
          rootModule,
          millClassloaderSigHash,
          millClassloaderIdentityHash,
          workerCache,
          env,
          !keepGoing,
          ec,
          codeSignatures,
          (reason: String, exitCode: Int) => systemExit(reason, exitCode),
          streams0,
          () => evaluator,
          offline,
          useFileLocks,
          workspaceLockManager,
          staticBuildOverrideFiles,
          enableTicker,
          depth,
          false, // isFinalDepth: set later via withIsFinalDepth when needed
          spanningInvalidationTree
        )
      ).asInstanceOf[EvaluatorApi]

    evaluator
  }

  def classpath(classLoader: ClassLoader): Vector[os.Path] = {

    var current = classLoader
    val files = Buffer.empty[os.Path]
    val seenClassLoaders = Buffer.empty[ClassLoader]
    while (current != null) {
      seenClassLoaders.append(current)
      current match {
        case t: java.net.URLClassLoader =>
          files.appendAll(
            t.getURLs
              .collect {
                case url if url.getProtocol == "file" => os.Path(java.nio.file.Paths.get(url.toURI))
              }
          )
        case _ =>
      }
      current = current.getParent
    }

    val sunBoot = System.getProperty("sun.boot.class.path")
    if (sunBoot != null) {
      files.appendAll(
        sunBoot
          .split(java.io.File.pathSeparator)
          .map(os.Path(_))
          .filter(os.exists(_))
      )
    } else {
      if (seenClassLoaders.contains(ClassLoader.getSystemClassLoader)) {
        for (p <- System.getProperty("java.class.path").split(File.pathSeparatorChar)) {
          val f = os.Path(p, BuildCtx.workspaceRoot)
          if (os.exists(f)) files.append(f)
        }
      }
    }
    files.toVector
  }
  def prepareMillBootClasspath(millBuildBase: os.Path): Seq[os.Path] = {
    val enclosingClasspath: Seq[os.Path] = classpath(getClass.getClassLoader)

    val selfClassURL = getClass.getProtectionDomain().getCodeSource().getLocation()
    assert(selfClassURL.getProtocol == "file")
    val selfClassLocation = os.Path(java.nio.file.Paths.get(selfClassURL.toURI))

    // Copy the current location of the enclosing classes to `mill-launcher.jar`
    // if it has the wrong file extension, because the Zinc incremental compiler
    // doesn't recognize classpath entries without the proper file extension
    val millLauncherOpt: Option[(os.Path, os.Path)] =
      if (
        os.isFile(selfClassLocation) &&
        !Set("zip", "jar", "class").contains(selfClassLocation.ext)
      ) {

        val millLauncher =
          millBuildBase / "mill-launcher" / s"${BuildInfo.millVersion}.jar"

        if (!os.exists(millLauncher)) {
          os.copy(selfClassLocation, millLauncher, createFolders = true, replaceExisting = true)
        }
        Some((selfClassLocation, millLauncher))
      } else None
    enclosingClasspath
      // avoid having the same file twice in the classpath
      .filter(f => millLauncherOpt.isEmpty || f != millLauncherOpt.get._1) ++
      millLauncherOpt.map(_._2)
  }

  def evaluateWithWatches(
      buildFileApi: BuildFileApi,
      evaluator: EvaluatorApi,
      tasksAndParams: Seq[String],
      selectiveExecution: Boolean,
      reporter: Int => Option[CompileProblemReporter]
  ): (Result[Seq[Any]], Seq[Watchable], Seq[Watchable]) = {
    import buildFileApi.*
    evalWatchedValues.clear()
    val evalTaskResult = evaluator.evaluate(
      tasksAndParams,
      SelectMode.Separated,
      reporter = reporter,
      selectiveExecution = selectiveExecution
    )

    evalTaskResult match {
      case f: Result.Failure =>
        (f, evalWatchedValues.toSeq, moduleWatchedValues)
      case Result.Success(res: EvaluatorApi.Result[Any]) =>
        res.values match {
          case f: Result.Failure =>
            (f, res.watchable ++ evalWatchedValues, moduleWatchedValues)
          case Result.Success(results) =>
            (Result.Success(results), res.watchable ++ evalWatchedValues, moduleWatchedValues)
        }
    }
  }

  def getRootModule(runClassLoader: URLClassLoader)
      : Result[BuildFileApi] = {
    // Instantiate BuildFileCls from the run classloader, passing the run classloader so it
    // can find the root module (package_) or fall back to DummyModule. This must be done via
    // the run classloader so that os.checker, BuildCtx, etc. are the run classloader versions.
    // BuildFileCls reads project root info from a classpath resource (rootModuleInfo.properties).
    val buildFileCls = runClassLoader.loadClass("mill.api.internal.BuildFileCls")
    val constructor = buildFileCls.getConstructor(classOf[ClassLoader])
    mill.api.ExecResult.catchWrapException {
      constructor.newInstance(runClassLoader).asInstanceOf[BuildFileApi]
    }
  }

  def recRoot(projectRoot: os.Path, depth: Int): os.Path = projectRoot / Seq.fill(depth)(millBuild)

  def recOut(output: os.Path, depth: Int): os.Path = output / Seq.fill(depth)(millBuild)

  def containsBuildFile(root: os.Path): Boolean =
    rootBuildFileNames.asScala.exists(name => os.exists(root / name))

  def tryReadParent(
      currentRoot: os.Path,
      fileName: String
  ): Option[(java.nio.file.Path, String)] = {
    val p = currentRoot / ".." / fileName
    Option.when(os.exists(p)) {
      p.toNIO -> mill.constants.Util.readBuildHeader(p.toNIO, fileName)
    }
  }

  def invalidLevelMsg(requestedMetaLevel: Option[Int], framesSize: Int): String =
    s"Invalid selected meta-level ${requestedMetaLevel.getOrElse(0)}. " +
      s"Valid range: 0 .. $framesSize (or -1 .. -${framesSize + 1})"

  // positive (0-based):  0 means workspace build,  1 means first meta-build, ...
  // negative (1-based): -1 means bootstrap build, -2 means one level above, ...
  def computeRequestedDepth(requestedMetaLevel: Option[Int], depth: Int, framesSize: Int): Int =
    requestedMetaLevel match {
      case Some(l) =>
        if (l >= 0) l
        else {
          val totalMetaLevels = depth + framesSize
          totalMetaLevels + 1 + l
        }
      case None =>
        0
    }

}
