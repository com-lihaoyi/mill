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
import mill.api.internal.WorkspaceLocking
import mill.meta.DiscoveredBuildFiles.findRootBuildFiles
import mill.server.Server
import mill.util.BuildInfo
import os.Path

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.collection.mutable.Buffer

/**
 * Logic around bootstrapping Mill, creating a [[MillBuildRootModule.BootstrapModule]]
 * and compiling builds/meta-builds and classloading their [[RootModule]]s so we
 * can evaluate the requested tasks on the [[RootModule]] representing the user's
 * `build.mill` file.
 *
 * When Mill is run in client-server mode, or with `--watch`, then reusable
 * meta-build data is shared across concurrent launchers via the per-daemon
 * [[sharedState]] (a [[RunnerSharedState]] holding the deterministic
 * shared frame at each depth, plus per-depth module-watch snapshots).
 * Per-launcher state — evaluators, watches, meta-build
 * read leases — is kept in [[prevCommandState]] and the returned [[RunnerLauncherState]]
 * so a subsequent watch iteration can pick up fallback classloaders to close
 * on refresh.
 *
 * When a subsequent evaluation happens, each level of [[evaluateRec]] serializes
 * meta-build compilation under a single process-wide meta-build write lock
 * (shared across all depths), then downgrades to a read lease once the reusable
 * frame has been finalized and published. That keeps compiled outputs,
 * classloader creation, and shared-state publish in one critical section while
 * still allowing downstream readers to share the published classloader.
 */
class MillBuildBootstrap(
    topLevelProjectRoot: os.Path,
    output: os.Path,
    keepGoing: Boolean,
    imports: Seq[String],
    env: Map[String, String],
    ec: Option[ThreadPoolExecutor],
    tasksAndParams: Seq[String],
    prevCommandState: RunnerLauncherState,
    logger: Logger,
    requestedMetaLevel: Option[Int],
    allowPositionalCommandArgs: Boolean,
    systemExit: Server.StopServer,
    streams0: SystemStreams,
    selectiveExecution: Boolean,
    offline: Boolean,
    useFileLocks: Boolean,
    workspaceLockManager: WorkspaceLocking.Manager,
    sharedState: AtomicReference[RunnerSharedState],
    reporter: EvaluatorApi => Int => Option[CompileProblemReporter],
    enableTicker: Boolean
) { outer =>
  import MillBuildBootstrap.*

  val millBootClasspath: Seq[os.Path] = prepareMillBootClasspath(output)
  val millBootClasspathPathRefs: Seq[PathRef] = millBootClasspath.map(PathRef(_, quick = true))

  def evaluate(): RunnerLauncherState = CliImports.withValue(imports) {
    val runnerLauncherState = evaluateRec(0)
    closeOnThrow(runnerLauncherState) {
      // Meta-build overlay logs go to shared canonical paths. Writes are idempotent
      // because the content is deterministic for a given published classloader —
      // concurrent launchers racing on the same path produce the same bytes.
      // Final-frame logs go to the same convention; concurrent launchers overwrite
      // each other's data at the final depth, which is acceptable since the log is
      // only consumed by the writer for debugging and by tests.
      def write(depth: Int, logged: RunnerLauncherState.Frame.Logged): Unit =
        os.write.over(
          recOut(output, depth) / millRunnerState,
          upickle.write(logged, indent = 4),
          createFolders = true
        )
      for (frame <- runnerLauncherState.metaBuildFrames)
        write(frame.depth, RunnerLauncherState.Frame.loggedForMetaBuild(frame))
      for (frame <- runnerLauncherState.finalFrame)
        write(frame.depth, RunnerLauncherState.Frame.loggedForFinal(frame))

      runnerLauncherState
    }
  }

  def evaluateRec(depth: Int): RunnerLauncherState =
    logger.withChromeProfile(s"meta-level $depth") {
      // println(s"+evaluateRec($depth) " + recRoot(projectRoot, depth))
      val currentRoot = recRoot(topLevelProjectRoot, depth)

      val nestedState =
        if (containsBuildFile(currentRoot)) evaluateRec(depth + 1)
        else makeBootstrapState(currentRoot)

      val nestedDepths = nestedState.processedDepths
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
        val rootModuleRes = nestedState.metaBuildFrames.headOption match {
          case None => Result.Success(BuildFileApi.Bootstrap(sharedState.get().bootstrapModule.get))
          case Some(nestedFrame) =>
            getRootModule(nestedFrame.sharedFrame.classLoaderOpt.get)
        }

        rootModuleRes match {
          case f: Result.Failure =>
            nestedState.withError(Util.formatError(f, logger.prompt.errorColor))

          case Result.Success(buildFileApi) =>
            // The returned RunnerLauncherState may hand these evaluators to BSP/IDE follow-up
            // work after bootstrap evaluation completes, so keep them alive on success
            // and let RunnerLauncherState.close() own their cleanup.
            val evaluator = makeEvaluator(nestedState, buildFileApi.rootModule, depth)
            closeOnThrow(evaluator) {
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

  private def makeBootstrapState(currentRoot: os.Path): RunnerLauncherState = {
    val (useDummy, foundRootBuildFileName) = findRootBuildFiles(topLevelProjectRoot)
    val bootstrapEvalWatched =
      Watchable.Path.from(PathRef(topLevelProjectRoot / foundRootBuildFileName))

    // Reuse the daemon-cached bootstrap module only when it was built against
    // the same root build file *and* the same dummy-vs-real bootstrap mode.
    // Caching only happens on success, so a bootstrap failure leaves the slot
    // empty and the next launcher retries.
    val cachedState = sharedState.get()
    val alreadyCached =
      cachedState.bootstrapBuildFile.contains(foundRootBuildFileName) &&
        cachedState.bootstrapUsesDummy.contains(useDummy)
    val error =
      if (alreadyCached) None
      else makeBootstrapModule(currentRoot, foundRootBuildFileName, useDummy) match {
        case Result.Success(bootstrapModule) =>
          sharedState.updateAndGet(
            _.withBootstrap(bootstrapModule, foundRootBuildFileName, useDummy)
          )
          None
        case f: Result.Failure => Some(Util.formatError(f, logger.prompt.errorColor))
      }

    RunnerLauncherState(
      errorOpt = error,
      buildFile = Some(foundRootBuildFileName),
      bootstrapEvalWatched = Seq(bootstrapEvalWatched)
    )
  }

  def makeEvaluator(
      nestedState: RunnerLauncherState,
      rootModule: RootModuleApi,
      depth: Int
  ): EvaluatorApi = {
    val currentRoot = recRoot(topLevelProjectRoot, depth)
    val staticBuildOverrides0 = tryReadParent(currentRoot, "build.mill.yaml")
      .orElse(tryReadParent(currentRoot, "build.mill"))

    // The classloader that will load the code at this depth lives on the meta-build
    // frame one level deeper (depth + 1). If there is no such frame, we're loading
    // from bootstrap and have no prior classloader to reference.
    val nestedMetaBuildFrame = nestedState.metaBuildFrames.headOption
    val nestedSharedFrame = nestedMetaBuildFrame.map(_.sharedFrame).filter(_.hasReusable)

    val staticBuildOverrideFiles =
      staticBuildOverrides0.toSeq ++ nestedSharedFrame.fold(Map.empty)(_.buildOverrideFiles)

    val millClassloaderIdentityHash0 = nestedSharedFrame
      .flatMap(_.classLoaderOpt)
      .map(_.identity)
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
      codeSignatures = nestedSharedFrame.map(_.codeSignatures).getOrElse(Map.empty),
      // Pass spanning tree only from a classloader refresh in this launch.
      spanningInvalidationTree = nestedMetaBuildFrame.flatMap(_.spanningInvalidationTree),
      rootModule = rootModule,
      // Use the current frame's runClasspath (includes mvnDeps and Mill jars) but filter out
      // compile.dest and generatedScriptSources.dest since build code changes are handled
      // by codesig analysis, not by classLoaderSigHash.
      millClassloaderSigHash = nestedSharedFrame match {
        case Some(frame) =>
          val compileDestPath = os.Path(frame.compileOutputOpt.get.javaPath)
          frame.runClasspath
            .filter { p =>
              val path = os.Path(p.javaPath)
              path != compileDestPath &&
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
      nestedState: RunnerLauncherState,
      buildFileApi: BuildFileApi,
      evaluator: EvaluatorApi,
      depth: Int
  ): RunnerLauncherState = {
    def acquireMetaBuildLock(kind: WorkspaceLocking.LockKind) =
      workspaceLockManager.acquireLock(WorkspaceLocking.metaBuildResource(kind))

    val writeLease = acquireMetaBuildLock(WorkspaceLocking.LockKind.Write)
    closeOnThrow(writeLease) {
      evaluateWithWatches(
        buildFileApi,
        evaluator,
        Seq("millBuildRootModuleResult"),
        selectiveExecution = false,
        reporter = reporter(evaluator)
      ) match {
        case (f: Result.Failure, evalWatches, moduleWatches) =>
          writeLease.close()
          nestedState
            .withMetaBuildFrame(
              RunnerLauncherState.MetaBuildFrame.failed(
                depth,
                evaluator,
                evalWatches,
                moduleWatches
              )
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
          // Prefer the shared state first: it reflects the provenance of the currently
          // published classloader we're about to reuse. Fall back to prevCommandState on
          // the first daemon command, before anything has published at this depth.
          def outerModuleWatched: Seq[Watchable] =
            sharedState.get().moduleWatchedAt(depth - 1)
              .orElse(prevCommandState.moduleWatchedAt(depth - 1))
              .getOrElse(Nil)

          def needsClassloaderRefresh(
              at: Option[RunnerSharedState.Frame]
          ): Boolean = {
            val runClasspathChanged =
              !at.exists(_.runClasspath.map(_.sig).sum == runClasspath.map(_.sig).sum)
            val moduleWatchChanged = outerModuleWatched.exists(w => !Watching.haveNotChanged(w))
            runClasspathChanged || moduleWatchChanged
          }

          def buildSharedFrame(classLoader: mill.api.MillURLClassLoader) =
            RunnerSharedState.Frame(
              moduleWatched = Some(moduleWatches),
              classLoaderOpt = Some(classLoader),
              runClasspath = runClasspath,
              compileOutputOpt = Some(compileClasses),
              codeSignatures = codeSignatures,
              buildOverrideFiles = buildOverrideFiles,
              workerCacheSummary = RunnerLauncherState.Frame.summarizeWorkerCache(
                evaluator.workerCache
              )
            )

          def launcherFrame(
              sharedFrame: RunnerSharedState.Frame,
              lease: WorkspaceLocking.ResourceLease,
              classLoaderChanged: Boolean
          ) = RunnerLauncherState.MetaBuildFrame(
            depth = depth,
            evaluator = evaluator,
            evalWatched = evalWatches,
            sharedFrame = sharedFrame,
            metaBuildReadLease = Some(lease),
            spanningInvalidationTree = Option.when(classLoaderChanged)(spanningInvalidationTree)
          )

          val latest = sharedState.get().frameAt(depth)
          val (sharedFrame, classLoaderChanged) =
            if (!needsClassloaderRefresh(latest) && latest.isDefined) (latest.get, false)
            else {
              // Close any classloaders we're about to replace. Workers at this depth
              // are handled by SharedWorkerCache.forDepth (called in makeEvaluator).
              Seq(
                prevCommandState.metaBuildFrameAt(depth)
                  .flatMap(_.sharedFrame.classLoaderOpt),
                latest.flatMap(_.classLoaderOpt)
              ).flatten.distinct.foreach(_.close())
              val fresh = buildSharedFrame(createClassLoader())
              sharedState.updateAndGet(_.withFrame(depth, fresh))
              (fresh, true)
            }

          // Publish this depth's moduleWatched snapshot so the depth-above launcher
          // can detect whether anything watched under this classloader has changed.
          // Mirrors what processFinalTasks does for final-depth tasks.
          sharedState.getAndUpdate(_.withModuleWatched(depth, moduleWatches))

          writeLease.downgradeToRead()
          nestedState.withMetaBuildFrame(launcherFrame(sharedFrame, writeLease, classLoaderChanged))

        case unknown => sys.error(unknown.toString())
      }
    }
  }

  /**
   * Handles the final evaluation of the user-provided tasks. Since there are
   * no further levels to evaluate, we do not need to save a `scriptImportGraph`,
   * classloader, or runClasspath.
   */
  def processFinalTasks(
      nestedState: RunnerLauncherState,
      buildFileApi: BuildFileApi,
      evaluator0: EvaluatorApi,
      depth: Int
  ): RunnerLauncherState = {
    val evaluator = evaluator0.withIsFinalDepth(true)
    val (evaled, evalWatched, moduleWatched) = evaluateWithWatches(
      buildFileApi = buildFileApi,
      evaluator = evaluator,
      tasksAndParams = tasksAndParams,
      selectiveExecution = selectiveExecution,
      reporter = reporter(evaluator)
    )

    val withFinal = nestedState.withFinalFrame(
      RunnerLauncherState.FinalFrame(depth, evaluator, evalWatched, moduleWatched)
    )
    sharedState.getAndUpdate(_.withModuleWatched(depth, moduleWatched))
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
