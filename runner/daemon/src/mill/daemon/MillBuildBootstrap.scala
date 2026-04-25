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
import mill.internal.Util
import mill.api.daemon.Watchable
import mill.api.internal.RootModule
import mill.internal.PrefixLogger
import mill.meta.{BootstrapRootModule, MillBuildRootModule}
import mill.api.daemon.internal.CliImports
import mill.api.daemon.internal.{LauncherOutFiles, LauncherLocking}
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
 * When a subsequent evaluation happens, each level of [[evaluateRec]] uses its
 * own per-depth meta-build read/write lock. Launchers first probe reuse under a
 * read lock, then only upgrade to a write lock when that depth must actually be
 * refreshed. After publishing a refreshed frame they downgrade back to read so
 * the classloader remains stable for the rest of the launch while unrelated
 * depths continue independently.
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
    workspaceLocking: LauncherLocking,
    runArtifacts: LauncherOutFiles,
    sharedState: AtomicReference[RunnerSharedState],
    reporter: EvaluatorApi => Int => Option[CompileProblemReporter],
    enableTicker: Boolean,
    disableFinalTaskShortCircuit: Boolean,
    priorBootstrap: Option[BspPriorBootstrap]
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
      for (frame <- runnerLauncherState.metaBuildFrames) write(frame.depth, frame.logged)
      for (frame <- runnerLauncherState.finalFrame) write(frame.depth, frame.logged)

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
      else {
        // Dedicated bootstrap lock — the bootstrap module is a single shared
        // slot in RunnerSharedState that isn't depth-keyed, so we don't reuse
        // any meta-build depth lock for it.
        val shareLease = workspaceLocking.bootstrapLock(LauncherLocking.LockKind.Write)
        try {
          val refreshed = sharedState.get()
          val stillMissing =
            !refreshed.bootstrapBuildFile.contains(foundRootBuildFileName) ||
              !refreshed.bootstrapUsesDummy.contains(useDummy)
          if (!stillMissing) None
          else makeBootstrapModule(currentRoot, foundRootBuildFileName, useDummy) match {
            case Result.Success(bootstrapModule) =>
              sharedState.updateAndGet(
                _.withBootstrap(bootstrapModule, foundRootBuildFileName, useDummy)
              )
              None
            case f: Result.Failure => Some(Util.formatError(f, logger.prompt.errorColor))
          }
        } finally shareLease.close()
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
    val workerCache = SharedWorkerCache.forDepth(depth, millClassloaderIdentityHash0)

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
    val readLease = workspaceLocking.metaBuildLock(depth, LauncherLocking.LockKind.Read)
    val taskSelector = Seq("millBuildRootModuleResult")
    val collectSelectiveMetadata = tasksAndParams.nonEmpty

    def outerModuleWatched: Seq[Watchable] =
      // Final-depth module watches are launcher-local, so keep the previous
      // watch-loop iteration's value as a fallback there. Meta-build depths
      // publish their latest watch snapshot into sharedState even on failure,
      // so sharedState remains the canonical source for parent invalidation.
      prevCommandState.finalModuleWatchedAt(depth - 1)
        .orElse(sharedState.get().moduleWatchedAt(depth - 1))
        .getOrElse(Nil)

    def watchedParentInputsChanged(): Boolean =
      outerModuleWatched.exists(w => !Watching.haveNotChanged(w))

    def reusable(frameOpt: Option[RunnerSharedState.Frame])
        : Result[Option[RunnerSharedState.Frame]] =
      frameOpt match {
        case None => Result.Success(None)
        case Some(_) if watchedParentInputsChanged() => Result.Success(None)
        case Some(frame) =>
          frame.selectiveMetadata match {
            case None => Result.Success(None)
            case Some(previousMetadata) =>
              evaluator
                .probeSelectiveMetadata(
                  taskSelector,
                  SelectMode.Separated,
                  previousMetadata
                ) match {
                case Result.Success((true, _)) => Result.Success(Some(frame))
                case Result.Success(_) => Result.Success(None)
                case _: Result.Failure => Result.Success(None)
              }
          }
      }

    def launcherFrame(
        sharedFrame: RunnerSharedState.Frame,
        lease: LauncherLocking.Lease,
        classLoaderChanged: Boolean,
        spanningInvalidationTree: Option[String] = None
    ) = RunnerLauncherState.MetaBuildFrame(
      depth = depth,
      evaluator = evaluator,
      evalWatched = sharedFrame.evalWatched,
      sharedFrame = sharedFrame,
      metaBuildReadLease = Some(lease),
      spanningInvalidationTree = Option.when(classLoaderChanged)(spanningInvalidationTree).flatten
    )

    def readSelectiveMetadataFile(): Option[String] = {
      val metadataFile = os.Path(evaluator.outPathJava) / OutFiles.millSelectiveExecution
      Option.when(os.exists(metadataFile))(os.read(metadataFile))
    }

    // Helpers that end the function in a single line, so the outer match
    // stays flat. Each closes the lease then returns the appropriate
    // RunnerLauncherState (error / cache hit / reuse / publish-fresh /
    // publish-failed-frame).
    def errorClosingLease(lease: LauncherLocking.Lease, f: Result.Failure): RunnerLauncherState = {
      lease.close()
      nestedState.withError(mill.internal.Util.formatError(f, logger.prompt.errorColor))
    }

    def reuseFrame(
        lease: LauncherLocking.Lease,
        frame: RunnerSharedState.Frame,
        downgradeFromWrite: Boolean
    ): RunnerLauncherState = {
      if (downgradeFromWrite) lease.downgradeToRead()
      nestedState.withMetaBuildFrame(launcherFrame(frame, lease, classLoaderChanged = false))
    }

    def closePriorAndPreviousClassloaders(previousShared: RunnerSharedState): Unit =
      Seq(
        prevCommandState.metaBuildFrameAt(depth).flatMap(_.sharedFrame.classLoaderOpt),
        previousShared.frames.get(depth).flatMap(_.classLoaderOpt)
      ).flatten.distinct.foreach(_.close())

    def publishFailedFrame(
        writeLease: LauncherLocking.Lease,
        f: Result.Failure,
        evalWatches: Seq[Watchable],
        moduleWatches: Seq[Watchable]
    ): RunnerLauncherState = {
      val failedShared = RunnerSharedState.Frame(
        evalWatched = evalWatches,
        moduleWatched = Some(moduleWatches)
      )
      val previous = sharedState.getAndUpdate(_.withFrame(depth, failedShared))
      closePriorAndPreviousClassloaders(previous)
      writeLease.close()
      nestedState
        .withMetaBuildFrame(
          RunnerLauncherState.MetaBuildFrame.failed(
            depth,
            evaluator,
            evalWatches,
            moduleWatches
          ).copy(sharedFrame = failedShared)
        )
        .withError(mill.internal.Util.formatError(f, logger.prompt.errorColor))
    }

    def publishFreshFrame(
        writeLease: LauncherLocking.Lease,
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
      // Build the new frame *first* so a failure in createClassLoader leaves
      // the previously-published frame intact and reusable. Old classloaders
      // are closed only after the new frame is successfully published.
      val fresh = RunnerSharedState.Frame(
        evalWatched = evalWatches,
        moduleWatched = Some(moduleWatches),
        classLoaderOpt = Some(createClassLoader()),
        runClasspath = runClasspath,
        compileOutputOpt = Some(compileClasses),
        codeSignatures = codeSignatures,
        buildOverrideFiles = buildOverrideFiles,
        workerCacheSummary =
          RunnerLauncherState.Frame.summarizeWorkerCache(evaluator.workerCache),
        selectiveMetadata =
          Option.when(collectSelectiveMetadata)(readSelectiveMetadataFile()).flatten
      )
      val previous = sharedState.getAndUpdate(_.withFrame(depth, fresh))
      closePriorAndPreviousClassloaders(previous)
      writeLease.downgradeToRead()
      nestedState.withMetaBuildFrame(launcherFrame(
        fresh,
        writeLease,
        classLoaderChanged = true,
        spanningInvalidationTree = Some(spanningInvalidationTree)
      ))
    }

    def evaluateAndPublish(writeLease: LauncherLocking.Lease): RunnerLauncherState = {
      val metadataFile = os.Path(evaluator.outPathJava) / OutFiles.millSelectiveExecution
      if (collectSelectiveMetadata && os.exists(metadataFile)) os.remove(metadataFile)
      evaluateWithWatches(
        buildFileApi,
        evaluator,
        taskSelector,
        selectiveExecution = collectSelectiveMetadata,
        reporter = reporter(evaluator)
      ) match {
        case (f: Result.Failure, evalWatches, moduleWatches) =>
          publishFailedFrame(writeLease, f, evalWatches, moduleWatches)
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
          publishFreshFrame(
            writeLease,
            runClasspath,
            compileClasses,
            codeSignatures,
            buildOverrideFiles,
            spanningInvalidationTree,
            evalWatches,
            moduleWatches
          )
        case unknown => sys.error(unknown.toString())
      }
    }

    // closeOnThrow guards each lease against a throw from `reusable` itself
    // (e.g. evaluator.probeSelectiveMetadata or Watching.haveNotChanged
    // throwing); without it, a leaked lease would block every concurrent
    // meta-build write at this depth until daemon shutdown.
    closeOnThrow(readLease) {
      reusable(sharedState.get().frameAt(depth))
    } match {
      case f: Result.Failure => errorClosingLease(readLease, f)
      case Result.Success(Some(frame)) => reuseFrame(readLease, frame, downgradeFromWrite = false)
      case Result.Success(None) =>
        readLease.close()
        val writeLease = workspaceLocking.metaBuildLock(depth, LauncherLocking.LockKind.Write)
        closeOnThrow(writeLease) {
          reusable(sharedState.get().frameAt(depth)) match {
            case f: Result.Failure => errorClosingLease(writeLease, f)
            case Result.Success(Some(frame)) =>
              reuseFrame(writeLease, frame, downgradeFromWrite = true)
            case Result.Success(None) => evaluateAndPublish(writeLease)
          }
        }
    }
  }

  /**
   * Handles the final evaluation of the user-provided tasks. Since there are
   * no further levels to evaluate, we do not need to save a `scriptImportGraph`,
   * classloader, or runClasspath.
   *
   * Short-circuit: if the previous launcher's final frame at this depth has
   * all watches still unchanged (file inputs, env vars, etc.) AND its tasks
   * matched what we are about to evaluate, skip the actual task evaluation
   * and reuse prev's watches. This avoids the cost of re-running tasks like
   * `resolve _` (used by BSP requests to populate evaluators) when nothing
   * has changed since the previous successful evaluation. The bypass is
   * gated on `disableFinalTaskShortCircuit` so the watch loop can force a
   * re-run on user input (Enter pressed) even when no watched file changed.
   */
  def processFinalTasks(
      nestedState: RunnerLauncherState,
      buildFileApi: BuildFileApi,
      evaluator0: EvaluatorApi,
      depth: Int
  ): RunnerLauncherState = {
    val evaluator = evaluator0.withIsFinalDepth(true)

    val shortCircuitSource: Option[(Seq[Watchable], Seq[Watchable])] =
      if (disableFinalTaskShortCircuit) None
      else
        prevCommandState.finalFrame
          .filter(f =>
            f.depth == depth && f.tasksAndParams == tasksAndParams
          )
          .map(f => (f.evalWatched, f.moduleWatched))
          .orElse(
            priorBootstrap
              .filter(p => p.finalDepth == depth && p.tasksAndParams == tasksAndParams)
              .map(p => (p.evalWatched, p.moduleWatched))
          )
          .filter { case (ev, mw) =>
            ev.forall(Watching.haveNotChanged) && mw.forall(Watching.haveNotChanged)
          }

    shortCircuitSource match {
      case Some((evalWatched, moduleWatched)) =>
        return nestedState.withFinalFrame(
          RunnerLauncherState.FinalFrame(
            depth = depth,
            evaluator = evaluator,
            evalWatched = evalWatched,
            moduleWatched = moduleWatched,
            tasksAndParams = tasksAndParams
          )
        )
      case None => ()
    }

    val (evaled, evalWatched, moduleWatched) = evaluateWithWatches(
      buildFileApi = buildFileApi,
      evaluator = evaluator,
      tasksAndParams = tasksAndParams,
      selectiveExecution = selectiveExecution,
      reporter = reporter(evaluator)
    )

    val withFinal = nestedState.withFinalFrame(
      RunnerLauncherState.FinalFrame(depth, evaluator, evalWatched, moduleWatched, tasksAndParams)
    )
    // Final-depth moduleWatched is intentionally NOT published into
    // [[sharedState]]. Different launchers select different module subsets
    // (e.g. `mill foo.compile` vs `mill bar.compile`) and produce disjoint
    // moduleWatched sets at the final depth; clobbering shared state with the
    // last-writer's set would let a concurrent meta-build reuse-check at
    // depth+1 read a watch set that doesn't correspond to any cached frame at
    // this depth, defeating the watchedParentInputsChanged() invalidation.
    // Final-depth moduleWatched lives only on this launcher's
    // [[RunnerLauncherState.FinalFrame]] (see
    // [[RunnerLauncherState.moduleWatchedAt]]) and feeds back into the same
    // launcher's `prevCommandState.moduleWatchedAt(depth-1)` fallback in
    // [[processRunClasspath]] on the next watch iteration.
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
