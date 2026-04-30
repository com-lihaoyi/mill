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
import mill.constants.OutFiles.OutFiles
import mill.api.daemon.Watchable
import mill.api.internal.RootModule
import mill.internal.{LockUpgrade, PrefixLogger, PromptWaitReporter, Util}
import mill.meta.{BootstrapRootModule, MillBuildRootModule}
import mill.api.daemon.internal.{CliImports, LauncherLocking, LauncherOutFiles}
import mill.internal.BuildFileDiscovery.findRootBuildFiles
import mill.server.Server
import mill.util.BuildInfo
import os.Path

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ThreadPoolExecutor
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.collection.mutable.Buffer

/**
 * Logic around bootstrapping Mill, creating a [[MillBuildRootModule.BootstrapModule]]
 * and compiling builds/meta-builds and classloading their [[RootModule]]s so we
 * can evaluate the requested tasks on the [[RootModule]] representing the user's
 * `build.mill` file.
 *
 * When Mill is run in daemon mode or with `--watch`, bootstrap state is cached
 * in-memory across evaluations. The daemon-wide reusable bootstrap metadata lives in
 * [[RunnerSharedState]], while each launcher keeps its own evaluators and retained
 * read leases in [[RunnerLauncherState]].
 *
 * When a subsequent evaluation happens, each meta-level attempts to re-use its
 * corresponding cached frame to avoid unnecessary classloader recreation,
 * worker churn, or re-evaluation. This should remain transparent to users
 * while improving performance.
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
    runArtifacts: LauncherOutFiles,
    metaBuild: MetaBuildAccess,
    reporter: EvaluatorApi => Int => Option[CompileProblemReporter],
    // Reporter for the meta-build (`build.mill`/`mill-build/build.mill`)
    // compile, keyed by depth. Distinct from `reporter` (which is keyed by
    // module hashCode) because at meta-build time we don't have a stable
    // module-hashCode mapping yet.
    metaBuildReporter: Int => Option[CompileProblemReporter] = _ => None,
    enableTicker: Boolean
) { outer =>
  // The workspace locking is owned by the metaBuild access (alongside the
  // shared state) but Execution still consumes the LauncherLocking directly
  // for per-task locks.
  private val workspaceLocking: LauncherLocking = metaBuild.workspaceLocking
  // Surfaces lock-wait status into the multi-line prompt's per-line detail
  // suffix (instead of scrolling stderr lines that collide with the prompt
  // repaint cycle). Falls back to stderr when the prompt isn't live.
  private val waitReporter = PromptWaitReporter.fromLogger(logger, streams0.err)
  import MillBuildBootstrap.*

  val millBootClasspath: Seq[os.Path] = prepareMillBootClasspath(output)
  val millBootClasspathPathRefs: Seq[PathRef] = millBootClasspath.map(PathRef(_, quick = true))

  def evaluate(): RunnerLauncherState = CliImports.withValue(imports) {
    val runnerLauncherState = evaluateRec(0)
    try {
      def write(depth: Int, logged: RunnerLauncherState.Logged): Unit =
        os.write.over(
          recOut(output, depth) / millRunnerState,
          upickle.write(logged, indent = 4),
          createFolders = true
        )
      for (frame <- runnerLauncherState.metaFrames)
        write(frame.depth, RunnerLauncherState.Logged.fromMetaFrame(frame))
      for (frame <- runnerLauncherState.finalFrame)
        write(frame.depth, RunnerLauncherState.Logged.fromFinalFrame(frame))

      runnerLauncherState
    } catch {
      case t: Throwable =>
        try runnerLauncherState.close()
        catch { case _: Throwable => () }
        throw t
    }
  }

  def evaluateRec(depth: Int): RunnerLauncherState =
    logger.withChromeProfile(s"meta-level $depth") {
      val currentRoot = recRoot(topLevelProjectRoot, depth)

      val nestedState =
        if (containsBuildFile(currentRoot)) evaluateRec(depth + 1)
        else {
          // Evict stale frames left over from a previous deeper run (e.g.
          // `mill-build/build.mill` was deleted). Skip Write escalation
          // when there's nothing to prune so the common case stays on Read.
          metaBuild.withMetaBuild(depth, waitReporter) { (state, _) =>
            if (state.frames.keysIterator.exists(_ > depth))
              LockUpgrade.Decision.Escalate
            else LockUpgrade.Decision.Complete(())
          } { writeScope =>
            writeScope.update(_.withoutFramesAbove(depth)._1)
          }
          makeBootstrapState(currentRoot)
        }

      val nestedDepths = nestedState.metaFrames.size + nestedState.finalFrame.size
      val requestedDepth = computeRequestedDepth(requestedMetaLevel, depth, nestedDepths)

      // If an earlier frame errored out, just propagate the error to this frame.
      if (nestedState.errorOpt.isDefined) nestedState
      else if (nestedState.finalFrame.isDefined) {
        // Final tasks already ran at a deeper level due to `--meta-level` or `@nonBootstrapped`.
        // The deeper level validated the requested depth, so skip the depth==0 range check
        // here — `metaFrames` does not grow when we just propagate, so the check would fire
        // spuriously for valid `--meta-level N` requests satisfied below.
        nestedState
      } else if (depth == 0 && (requestedDepth > nestedDepths || requestedDepth < 0)) {
        // User has requested a frame depth that does not exist.
        nestedState.withError(invalidLevelMsg(requestedMetaLevel, nestedDepths))
      } else {
        val rootModuleRes = nestedState.metaFrames.headOption match {
          case None =>
            Result.Success(BuildFileApi.Bootstrap(nestedState.bootstrapModuleOpt.get))
          case Some(nestedFrame) =>
            getRootModule(nestedFrame.classLoaderOpt.get)
        }

        rootModuleRes match {
          case f: Result.Failure =>
            nestedState.withError(Util.formatError(f, logger.prompt.errorColor))

          case Result.Success(buildFileApi) =>
            val evaluator = makeEvaluator(nestedState, buildFileApi.rootModule, depth)
            try {
              val shouldShortCircuit =
                // When there is an explicit `--meta-level`, use that and ignore any
                // `@nonBootstrapped` annotations.
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

                case _ =>
                  // For both Success(false) and Failure, proceed with normal evaluation.
                  // If areAllNonBootstrapped failed (e.g. the task does not exist), the
                  // actual evaluation will also fail, but moduleWatched will still be captured.
                  if (depth > requestedDepth) {
                    processRunClasspath(nestedState, buildFileApi, evaluator, depth)
                  } else if (depth == requestedDepth) {
                    processFinalTasks(nestedState, buildFileApi, evaluator, depth)
                  } else {
                    // Out-of-range meta-level: propagate so the depth==0
                    // range check produces `invalidLevelMsg` instead of crashing.
                    nestedState
                  }
              }
            } catch {
              case t: Throwable =>
                // Close deeper-level evaluators that already returned a
                // partial `nestedState`; otherwise they leak when an outer
                // level throws after the deeper recursion succeeded.
                try evaluator.close()
                catch { case _: Throwable => () }
                try nestedState.close()
                catch { case _: Throwable => () }
                throw t
            }
        }
      }
    }

  private def makeBootstrapState(currentRoot: os.Path): RunnerLauncherState = {
    val (useDummy, foundRootBuildFileName) = findRootBuildFiles(topLevelProjectRoot)
    val bootstrapEvalWatched =
      Watchable.Path.from(PathRef(topLevelProjectRoot / foundRootBuildFileName))
    val (bootstrapModuleOpt, error) =
      makeBootstrapModule(currentRoot, foundRootBuildFileName, useDummy) match {
        case Result.Success(bootstrapModule) => (Some(bootstrapModule), None)
        case f: Result.Failure => (None, Some(Util.formatError(f, logger.prompt.errorColor)))
      }

    RunnerLauncherState(
      errorOpt = error,
      buildFile = Some(foundRootBuildFileName),
      bootstrapModuleOpt = bootstrapModuleOpt,
      buildFileWatch = Some(bootstrapEvalWatched)
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

    // The most recent meta-build frame is the just-published nested level,
    // pinned by this launcher through its retained read lease.
    val nestedMetaBuildFrame = nestedState.metaFrames.headOption
    val nestedSharedFrame = nestedMetaBuildFrame.flatMap(_.sharedFrame.reusable)

    val staticBuildOverrideFiles =
      staticBuildOverrides0.toSeq ++ nestedSharedFrame.fold(Map.empty)(_.buildOverrideFiles)

    val millClassloaderIdentityHash0 = nestedSharedFrame.map(_.classLoader.identity).getOrElse(0)

    // Worker lifetime tracks the classloader that loaded them. The
    // deepest level has no nested classloader, so its workers go into
    // the daemon-wide `bootstrapWorkers` map (loaded by the main
    // classloader, stable across launchers).
    val workerCache: collection.mutable.Map[String, (Int, Val, TaskApi[?])] =
      nestedSharedFrame
        .map(_.workers)
        .getOrElse(metaBuild.snapshot().bootstrapWorkers)

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
      workspaceLocking = workspaceLocking,
      runArtifacts = runArtifacts,
      workerCache = workerCache,
      codeSignatures = nestedSharedFrame.map(_.codeSignatures).getOrElse(Map.empty),
      spanningInvalidationTree = nestedMetaBuildFrame.flatMap(_.spanningInvalidationTree),
      rootModule = rootModule,
      // Use the current frame's runClasspath (includes mvnDeps and Mill jars) but filter out
      // compile.dest and generatedScriptSources.dest since build code changes are handled
      // by codesig analysis, not by classLoaderSigHash.
      millClassloaderSigHash = nestedSharedFrame match {
        case Some(frame) =>
          val compileDestPath = os.Path(frame.compileOutput.javaPath)
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
    val taskSelector = Seq("millBuildRootModuleResult")
    val collectSelectiveMetadata = tasksAndParams.nonEmpty
    type SharedFrame = RunnerSharedState.Frame
    case class ReuseProbe(frameOpt: Option[SharedFrame], reusableFrameOpt: Option[SharedFrame])

    // Module watching is one level offset: the watches produced by depth - 1 determine
    // whether the classloader published at this depth is still safe to reuse.
    // Lock-free read of the (depth-1) frame is safe because we hold a read
    // lease on it via `prevCommandState`/`nestedState.metaFrames`.
    def outerModuleWatched: Seq[Watchable] =
      prevCommandState.finalModuleWatchedAt(depth - 1)
        .orElse(metaBuild.snapshot().moduleWatchedAt(depth - 1))
        .getOrElse(Nil)

    def watchedParentInputsChanged(): Boolean =
      outerModuleWatched.exists(w => !Watching.haveNotChanged(w))

    def reusable(frameOpt: Option[SharedFrame]): Result[ReuseProbe] =
      frameOpt match {
        case None => Result.Success(ReuseProbe(None, None))
        case Some(frame) if frame.reusable.isEmpty =>
          Result.Success(ReuseProbe(frameOpt, None))
        case Some(_) if watchedParentInputsChanged() => Result.Success(ReuseProbe(frameOpt, None))
        case Some(frame) =>
          frame.reusable.flatMap(_.selectiveMetadata.get()) match {
            case None => Result.Success(ReuseProbe(frameOpt, None))
            case Some(previousMetadata) =>
              evaluator
                .probeSelectiveReuse(
                  taskSelector,
                  SelectMode.Separated,
                  previousMetadata
                ) match {
                case Result.Success(decision) =>
                  Result.Success(ReuseProbe(frameOpt, Option.when(decision.reusable)(frame)))
                case _: Result.Failure =>
                  Result.Success(ReuseProbe(frameOpt, None))
              }
          }
      }

    def readSelectiveMetadataFile(): Option[String] = {
      val metadataFile = os.Path(evaluator.outPathJava) / OutFiles.millSelectiveExecution
      Option.when(os.exists(metadataFile))(os.read(metadataFile))
    }

    def collectedSelectiveMetadata: Option[String] =
      Option.when(collectSelectiveMetadata)(readSelectiveMetadataFile()).flatten

    def pathRefSignature(pathRef: PathRefApi): (os.Path, Int) =
      os.Path(pathRef.javaPath) -> pathRef.sig

    def classloaderOutputSignature(
        runClasspath: Seq[PathRefApi],
        compileClasses: PathRefApi
    ): (Seq[(os.Path, Int)], (os.Path, Int)) =
      (runClasspath.map(pathRefSignature), pathRefSignature(compileClasses))

    def classloaderOutputUnchanged(
        reusable: RunnerSharedState.Frame.Reusable,
        runClasspath: Seq[PathRefApi],
        compileClasses: PathRefApi
    ): Boolean =
      classloaderOutputSignature(reusable.runClasspath, reusable.compileOutput) ==
        classloaderOutputSignature(runClasspath, compileClasses)

    def errorState(f: Result.Failure): RunnerLauncherState =
      nestedState.withError(mill.internal.Util.formatError(f, logger.prompt.errorColor))

    def reuseFrame(
        sharedFrame: RunnerSharedState.Frame,
        lease: LauncherLocking.Lease
    ): RunnerLauncherState =
      nestedState.withMetaFrame(
        RunnerLauncherState.MetaFrame(
          depth = depth,
          evaluator = evaluator,
          sharedFrame = sharedFrame,
          readLease = Some(lease),
          spanningInvalidationTree = None
        )
      )

    def closeDisplacedClassloader(
        displacedReusable: Option[RunnerSharedState.Frame.Reusable]
    ): Unit = {
      // Close any workers that lived on the displaced frame (their classloader is going
      // away). Done in reverse-topological order so dependent workers close before
      // their dependencies.
      displacedReusable.foreach { reusable =>
        val snapshot = reusable.workers.synchronized(reusable.workers.toMap)
        val deps = mill.exec.GroupExecution.workerDependencies(snapshot)
        val topoIndex = deps.iterator.map(_._1).zipWithIndex.toMap
        mill.exec.GroupExecution.closeWorkersInReverseTopologicalOrder(
          topoIndex.keys,
          reusable.workers,
          topoIndex,
          c =>
            try c.close()
            catch { case _: Throwable => () }
        )
        reusable.classLoader.close()
      }
    }

    def pruneFramesAbove(
        writeScope: MetaBuildAccess.WriteScope,
        maxDepth: Int
    ): Unit = {
      // Drop the map entries only. Acquiring deeper depth-write locks
      // here would invert the deepest-first lock order and deadlock; the
      // displaced classloaders become GC-eligible once their pinning
      // launchers complete (and `RunnerSharedStateOps.closeAll` closes
      // any survivors at daemon teardown).
      writeScope.update(_.withoutFramesAbove(maxDepth)._1)
    }

    // True at the deepest meta-build level visited this run; gates the
    // pruning of orphaned `RunnerSharedState.frames` at greater depths.
    val isDeepestLevel = nestedState.metaFrames.isEmpty

    def publishFailedFrame(
        writeScope: MetaBuildAccess.WriteScope,
        f: Result.Failure,
        evalWatches: Seq[Watchable],
        moduleWatches: Seq[Watchable]
    ): RunnerLauncherState = {
      val failedShared = RunnerSharedState.Frame(evalWatches, moduleWatches, None)
      val displaced = writeScope.update(_.withFrame(depth, failedShared)).reusableFrameAt(depth)
      closeDisplacedClassloader(displaced)
      if (isDeepestLevel) pruneFramesAbove(writeScope, depth)
      nestedState
        .withMetaFrame(
          RunnerLauncherState.MetaFrame(
            depth = depth,
            evaluator = evaluator,
            sharedFrame = failedShared,
            readLease = None,
            spanningInvalidationTree = None
          )
        )
        .withError(mill.internal.Util.formatError(f, logger.prompt.errorColor))
    }

    def publishFreshFrame(
        writeScope: MetaBuildAccess.WriteScope,
        runClasspath: Seq[PathRefApi],
        compileClasses: PathRefApi,
        codeSignatures: Map[String, Int],
        buildOverrideFiles: Map[java.nio.file.Path, String],
        spanningInvalidationTree: String,
        evalWatches: Seq[Watchable],
        moduleWatches: Seq[Watchable]
    ): RunnerLauncherState = {
      def createClassLoader() = {
        val rootModuleInfoDir = recOut(output, depth) / "rootModuleInfo.dest"
        // Write root module info as a classpath resource so BuildFileCls can
        // read it from the classloader without needing mutable global state.
        // This classloader will be used at depth - 1 to load the root module.
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
          sharedPrefixes =
            Seq("java.", "javax.", "scala.", "mill.api.daemon", "sbt.testing.")
        )
      }
      val fresh = RunnerSharedState.Frame(
        evalWatched = evalWatches,
        moduleWatched = moduleWatches,
        reusable = Some(RunnerSharedState.Frame.Reusable(
          classLoader = createClassLoader(),
          runClasspath = runClasspath,
          compileOutput = compileClasses,
          codeSignatures = codeSignatures,
          buildOverrideFiles = buildOverrideFiles,
          selectiveMetadata = new java.util.concurrent.atomic.AtomicReference(
            collectedSelectiveMetadata
          )
        ))
      )
      val displaced = writeScope.update(_.withFrame(depth, fresh)).reusableFrameAt(depth)
      closeDisplacedClassloader(displaced)
      if (isDeepestLevel) pruneFramesAbove(writeScope, depth)
      // Downgrade only after the displaced classloader has been closed, so
      // teardown of the old classloader/workers stays exclusive at this depth.
      val retainedLease = writeScope.scope.downgradeAndRetain()
      nestedState.withMetaFrame(
        RunnerLauncherState.MetaFrame(
          depth = depth,
          evaluator = evaluator,
          sharedFrame = fresh,
          readLease = Some(retainedLease),
          spanningInvalidationTree = Some(spanningInvalidationTree)
        )
      )
    }

    def publishUpdatedFrameWithExistingClassloader(
        writeScope: MetaBuildAccess.WriteScope,
        previousFrame: RunnerSharedState.Frame,
        previousReusable: RunnerSharedState.Frame.Reusable,
        runClasspath: Seq[PathRefApi],
        compileClasses: PathRefApi,
        codeSignatures: Map[String, Int],
        buildOverrideFiles: Map[java.nio.file.Path, String],
        evalWatches: Seq[Watchable],
        moduleWatches: Seq[Watchable]
    ): RunnerLauncherState = {
      if (collectSelectiveMetadata) {
        previousReusable.selectiveMetadata.set(collectedSelectiveMetadata)
      }

      val updatedReusable = previousReusable.copy(
        runClasspath = runClasspath,
        compileOutput = compileClasses,
        codeSignatures = codeSignatures,
        buildOverrideFiles = buildOverrideFiles
      )
      val updatedFrame = previousFrame.copy(
        evalWatched = evalWatches,
        moduleWatched = moduleWatches,
        reusable = Some(updatedReusable)
      )

      writeScope.update(_.withFrame(depth, updatedFrame))
      if (isDeepestLevel) pruneFramesAbove(writeScope, depth)
      val retainedLease = writeScope.scope.downgradeAndRetain()
      nestedState.withMetaFrame(
        RunnerLauncherState.MetaFrame(
          depth = depth,
          evaluator = evaluator,
          sharedFrame = updatedFrame,
          readLease = Some(retainedLease),
          spanningInvalidationTree = None
        )
      )
    }

    def evaluateAndPublish(writeScope: MetaBuildAccess.WriteScope): RunnerLauncherState = {
      val metadataFile = os.Path(evaluator.outPathJava) / OutFiles.millSelectiveExecution
      if (collectSelectiveMetadata && os.exists(metadataFile)) os.remove(metadataFile)
      // For meta-build compile, prefer the depth-keyed `metaBuildReporter` so
      // diagnostics are attached to the meta-build BSP target. Fall back to
      // the module-hashCode-keyed `reporter` if no meta-build reporter
      // exists (CLI mode or non-BSP runs).
      val metaReporter = metaBuildReporter(depth)
      val combinedReporter: Int => Option[CompileProblemReporter] =
        metaReporter match {
          case Some(_) => _ => metaReporter
          case None => reporter(evaluator)
        }
      evaluateWithWatches(
        buildFileApi,
        evaluator,
        taskSelector,
        selectiveExecution = collectSelectiveMetadata,
        reporter = combinedReporter
      ) match {
        case (f: Result.Failure, evalWatches, moduleWatches) =>
          publishFailedFrame(writeScope, f, evalWatches, moduleWatches)
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
          // Even when the meta-build's compiled output is bit-identical to
          // the previous frame, we cannot reuse the existing classloader if
          // the outer (one-level-up) `moduleWatched` has changed. The user's
          // `package build` is a Scala singleton (`MODULE$`) cached in that
          // classloader, so any `BuildCtx.watchValue(...)` results captured
          // during its initialization are frozen until the classloader is
          // recreated. See `9-dynamic-cross-modules` for a concrete case.
          val outerInputsChanged = watchedParentInputsChanged()
          writeScope.snapshot().frameAt(depth).flatMap { frame =>
            frame.reusable.collect {
              case reusable
                  if !outerInputsChanged &&
                    classloaderOutputUnchanged(reusable, runClasspath, compileClasses) =>
                publishUpdatedFrameWithExistingClassloader(
                  writeScope,
                  frame,
                  reusable,
                  runClasspath,
                  compileClasses,
                  codeSignatures,
                  buildOverrideFiles,
                  evalWatches,
                  moduleWatches
                )
            }
          }.getOrElse {
            publishFreshFrame(
              writeScope,
              runClasspath,
              compileClasses,
              codeSignatures,
              buildOverrideFiles,
              spanningInvalidationTree,
              evalWatches,
              moduleWatches
            )
          }
        case unknown => sys.error(unknown.toString())
      }
    }

    var readProbeOpt = Option.empty[ReuseProbe]
    metaBuild.withMetaBuild(depth, waitReporter) { (state, scope) =>
      reusable(state.frameAt(depth)) match {
        case f: Result.Failure =>
          LockUpgrade.Decision.Complete(errorState(f))
        case Result.Success(probe) =>
          readProbeOpt = Some(probe)
          probe.reusableFrameOpt match {
            case Some(frame) =>
              LockUpgrade.Decision.Complete(reuseFrame(frame, scope.retain()))
            case None => LockUpgrade.Decision.Escalate
          }
      }
    } { writeScope =>
      val currentFrame = writeScope.snapshot().frameAt(depth)
      val reuseResult = readProbeOpt.filter(_.frameOpt == currentFrame) match {
        case Some(probe) => Result.Success(probe)
        case None => reusable(currentFrame)
      }

      reuseResult match {
        case f: Result.Failure =>
          errorState(f)
        case Result.Success(probe) =>
          probe.reusableFrameOpt match {
            case Some(frame) => reuseFrame(frame, writeScope.scope.downgradeAndRetain())
            case None => evaluateAndPublish(writeScope)
          }
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

    // Publish the user-level (depth == 0) moduleWatched daemon-wide so the
    // *next* launcher's depth-1 reusable check sees these watches as the
    // "outer" inputs and recreates the meta-build classloader when they
    // change (e.g. `BuildCtx.watchValue` over `os.list(...)` after the
    // listed directory was modified). Without this, only meta-build frames
    // (depth >= 1) survive across launchers, and the user-level watches
    // would be lost.
    if (depth == 0) {
      metaBuild.publishUserFinalModuleWatched(moduleWatched)

      // If user-level evaluation mutated meta-build inputs (e.g. spotless
      // reformatting `build.mill`), refresh each enclosing meta-build
      // frame's `selectiveMetadata` so the next launcher doesn't trigger
      // a spurious meta-build rebuild.
      nestedState.metaFrames.foreach { metaFrame =>
        metaFrame.sharedFrame.reusable.foreach { reusable =>
          val previous = reusable.selectiveMetadata.get()
          previous.foreach { prev =>
            metaFrame.evaluator.probeSelectiveReuse(
              Seq("millBuildRootModuleResult"),
              SelectMode.Separated,
              prev
            ) match {
              // Only advance metadata when reusable: otherwise the
              // in-memory classloader is from pre-edit code and a stale
              // metadata advance would trick the next launcher into reusing it.
              case Result.Success(decision) if decision.reusable =>
                reusable.selectiveMetadata.set(Some(decision.nextMetadata))
                val metadataFile =
                  os.Path(metaFrame.evaluator.outPathJava) / OutFiles.millSelectiveExecution
                // Atomic write: runs under the meta-build Read lease, so
                // concurrent launchers can race here; a torn file would
                // mis-trigger a meta-build rebuild on the next read.
                try {
                  val tmp = metadataFile / os.up /
                    s".${metadataFile.last}.tmp-${System.nanoTime()}-${ProcessHandle.current().pid()}"
                  try {
                    os.write.over(tmp, decision.nextMetadata, createFolders = true)
                    java.nio.file.Files.move(
                      tmp.toNIO,
                      metadataFile.toNIO,
                      java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                      java.nio.file.StandardCopyOption.ATOMIC_MOVE
                    )
                  } finally {
                    try os.remove.all(tmp)
                    catch { case _: Throwable => () }
                  }
                } catch { case _: Throwable => () }
              case _ => ()
            }
          }
        }
      }
    }

    val failed = evaled.isInstanceOf[Result.Failure]
    val withFinal = nestedState.withFinalFrame(
      RunnerLauncherState.FinalFrame(
        depth = depth,
        evaluator = evaluator,
        evalWatched = evalWatched,
        moduleWatched = moduleWatched,
        tasksAndParams = tasksAndParams,
        failed = failed
      )
    )
    evaled match {
      case f: Result.Failure =>
        withFinal.withError(mill.internal.Util.formatError(f, logger.prompt.errorColor))
      case _ => withFinal
    }
  }

}

object MillBuildBootstrap {

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
      workspaceLocking: LauncherLocking,
      runArtifacts: LauncherOutFiles,
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
          workspaceLocking,
          runArtifacts,
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
    val (evalTaskResult, evalWatched) = withEvalWatchedValues {
      evaluator.evaluate(
        tasksAndParams,
        SelectMode.Separated,
        reporter = reporter,
        selectiveExecution = selectiveExecution
      )
    }

    evalTaskResult match {
      case f: Result.Failure =>
        (f, evalWatched, moduleWatchedValues)
      case Result.Success(res: EvaluatorApi.Result[Any]) =>
        res.values match {
          case f: Result.Failure =>
            (f, res.watchable ++ evalWatched, moduleWatchedValues)
          case Result.Success(results) =>
            (Result.Success(results), res.watchable ++ evalWatched, moduleWatchedValues)
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
