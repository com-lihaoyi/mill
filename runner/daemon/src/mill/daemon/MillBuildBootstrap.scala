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
import mill.internal.{LockUpgrade, PrefixLogger, Util}
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
    workspaceLocking: LauncherLocking,
    runArtifacts: LauncherOutFiles,
    sharedState: AtomicReference[RunnerSharedState],
    reporter: EvaluatorApi => Int => Option[CompileProblemReporter],
    enableTicker: Boolean,
    skipSelectiveExecution: Boolean
) { outer =>
  import MillBuildBootstrap.*

  val millBootClasspath: Seq[os.Path] = prepareMillBootClasspath(output)
  val millBootClasspathPathRefs: Seq[PathRef] = millBootClasspath.map(PathRef(_, quick = true))

  def evaluate(): RunnerLauncherState = CliImports.withValue(imports) {
    val runnerLauncherState = evaluateRec(0)
    closeOnThrow(runnerLauncherState) {
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
      val currentRoot = recRoot(topLevelProjectRoot, depth)

      val nestedState =
        if (containsBuildFile(currentRoot)) evaluateRec(depth + 1)
        else makeBootstrapState(currentRoot, depth)

      val nestedDepths = nestedState.processedDepths
      val requestedDepth = computeRequestedDepth(requestedMetaLevel, depth, nestedDepths)

      // If an earlier frame errored out, just propagate the error to this frame.
      if (nestedState.errorOpt.isDefined) nestedState
      else if (depth == 0 && (requestedDepth > nestedDepths || requestedDepth < 0)) {
        // User has requested a frame depth that does not exist.
        nestedState.withError(invalidLevelMsg(requestedMetaLevel, nestedDepths))
      } else if (nestedState.finalFrame.isDefined) {
        // Final tasks already ran at a deeper level due to `--meta-level` or `@nonBootstrapped`.
        nestedState
      } else {
        val rootModuleRes = nestedState.metaBuildFrames.headOption match {
          case None =>
            Result.Success(
              BuildFileApi.Bootstrap(sharedState.get().bootstrap.get.module)
            )
          case Some(nestedFrame) =>
            getRootModule(nestedFrame.classLoaderOpt.get)
        }

        rootModuleRes match {
          case f: Result.Failure =>
            nestedState.withError(Util.formatError(f, logger.prompt.errorColor))

          case Result.Success(buildFileApi) =>
            val evaluator = makeEvaluator(nestedState, buildFileApi.rootModule, depth)
            closeOnThrow(evaluator) {
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
                  } else ??? // should be handled by outer conditional
              }
            }
        }
      }
    }

  private def makeBootstrapState(currentRoot: os.Path, depth: Int): RunnerLauncherState = {
    val (useDummy, foundRootBuildFileName) = findRootBuildFiles(topLevelProjectRoot)
    val bootstrapEvalWatched =
      Watchable.Path.from(PathRef(topLevelProjectRoot / foundRootBuildFileName))

    def alreadyCached(state: RunnerSharedState): Boolean = state.bootstrap.exists(cache =>
      cache.buildFile == foundRootBuildFileName && cache.usesDummy == useDummy
    )
    val error = LockUpgrade.readThenWrite(
      acquireRead = workspaceLocking.metaBuildLock(depth, LauncherLocking.LockKind.Read),
      acquireWrite = workspaceLocking.metaBuildLock(depth, LauncherLocking.LockKind.Write)
    ) { _ =>
      if (alreadyCached(sharedState.get())) LockUpgrade.Decision.Complete(None)
      else LockUpgrade.Decision.Escalate
    } { _ =>
      if (alreadyCached(sharedState.get())) None
      else
        makeBootstrapModule(currentRoot, foundRootBuildFileName, useDummy) match {
          case Result.Success(bootstrapModule) =>
            sharedState.updateAndGet(
              _.withBootstrap(
                RunnerSharedState.BootstrapCache(
                  bootstrapModule,
                  foundRootBuildFileName,
                  useDummy
                )
              )
            )
            None
          case f: Result.Failure => Some(Util.formatError(f, logger.prompt.errorColor))
        }
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

    val nestedMetaBuildFrame = nestedState.metaBuildFrames.headOption
    val nestedSharedFrame = nestedMetaBuildFrame.flatMap(frame =>
      sharedState.get().reusableFrameAt(frame.depth)
    )

    val staticBuildOverrideFiles =
      staticBuildOverrides0.toSeq ++ nestedSharedFrame.fold(Map.empty)(_.buildOverrideFiles)

    val millClassloaderIdentityHash0 = nestedSharedFrame.map(_.classLoader.identity).getOrElse(0)

    // sharedWorkerCache drops and replaces the cache when the classloader identity changes.
    val workerCache =
      RunnerSharedState.sharedWorkerCache(sharedState, depth, millClassloaderIdentityHash0)

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
    type SharedFrame = RunnerSharedState.Frame.Reusable
    case class ReuseProbe(frameOpt: Option[SharedFrame], reusableFrameOpt: Option[SharedFrame])

    // Module watching is one level offset: the watches produced by depth - 1 determine
    // whether the classloader published at this depth is still safe to reuse.
    def outerModuleWatched: Seq[Watchable] =
      prevCommandState.finalModuleWatchedAt(depth - 1)
        .orElse(sharedState.get().moduleWatchedAt(depth - 1))
        .getOrElse(Nil)

    def watchedParentInputsChanged(): Boolean =
      outerModuleWatched.exists(w => !Watching.haveNotChanged(w))

    def reusable(frameOpt: Option[SharedFrame]): Result[ReuseProbe] =
      frameOpt match {
        case None => Result.Success(ReuseProbe(None, None))
        case Some(_) if watchedParentInputsChanged() => Result.Success(ReuseProbe(frameOpt, None))
        case Some(frame) =>
          frame.selectiveMetadata match {
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

    def errorState(f: Result.Failure): RunnerLauncherState =
      nestedState.withError(mill.internal.Util.formatError(f, logger.prompt.errorColor))

    def reuseFrame(
        frame: SharedFrame,
        lease: LauncherLocking.Lease
    ): RunnerLauncherState =
      nestedState.withMetaBuildFrame(
        RunnerLauncherState.MetaBuildFrame.reusable(
          depth,
          evaluator,
          frame,
          lease,
          spanningInvalidationTree = None
        )
      )

    def closePriorAndPreviousClassloaders(previousShared: RunnerSharedState): Unit =
      // Make sure we close old classloaders every time we publish a replacement, to avoid
      // leaking classloaders and workers that may depend on them.
      Seq(
        prevCommandState.metaBuildFrameAt(depth).flatMap(_.classLoaderOpt),
        previousShared.reusableFrameAt(depth).map(_.classLoader)
      ).flatten.distinct.foreach(_.close())

    def publishFailedFrame(
        f: Result.Failure,
        evalWatches: Seq[Watchable],
        moduleWatches: Seq[Watchable]
    ): RunnerLauncherState = {
      val failedShared = RunnerSharedState.Frame.Failed(
        evalWatched = evalWatches,
        moduleWatched = moduleWatches
      )
      val previous = sharedState.getAndUpdate(_.withFrame(depth, failedShared))
      closePriorAndPreviousClassloaders(previous)
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
    }

    def publishFreshFrame(
        retainedLease: LauncherLocking.Lease,
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
      val fresh = RunnerSharedState.Frame.Reusable(
        evalWatched = evalWatches,
        moduleWatched = moduleWatches,
        classLoader = createClassLoader(),
        runClasspath = runClasspath,
        compileOutput = compileClasses,
        codeSignatures = codeSignatures,
        buildOverrideFiles = buildOverrideFiles,
        selectiveMetadata =
          Option.when(collectSelectiveMetadata)(readSelectiveMetadataFile()).flatten
      )
      val previous = sharedState.getAndUpdate(_.withFrame(depth, fresh))
      closePriorAndPreviousClassloaders(previous)
      nestedState.withMetaBuildFrame(
        RunnerLauncherState.MetaBuildFrame.reusable(
          depth,
          evaluator,
          fresh,
          retainedLease,
          spanningInvalidationTree = Some(spanningInvalidationTree)
        )
      )
    }

    def evaluateAndPublish(scope: LockUpgrade.Scope): RunnerLauncherState = {
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
          publishFailedFrame(f, evalWatches, moduleWatches)
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
            scope.downgradeAndRetain(),
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

    var readProbeOpt = Option.empty[ReuseProbe]
    LockUpgrade.readThenWrite(
      acquireRead = workspaceLocking.metaBuildLock(depth, LauncherLocking.LockKind.Read),
      acquireWrite = workspaceLocking.metaBuildLock(depth, LauncherLocking.LockKind.Write)
    ) { scope =>
      reusable(sharedState.get().reusableFrameAt(depth)) match {
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
    } { scope =>
      val currentFrame = sharedState.get().reusableFrameAt(depth)
      val reuseResult = readProbeOpt.filter(_.frameOpt == currentFrame) match {
        case Some(probe) => Result.Success(probe)
        case None => reusable(currentFrame)
      }

      reuseResult match {
        case f: Result.Failure =>
          errorState(f)
        case Result.Success(probe) =>
          probe.reusableFrameOpt match {
            case Some(frame) => reuseFrame(frame, scope.downgradeAndRetain())
            case None => evaluateAndPublish(scope)
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

    val shortCircuitSource: Option[(Seq[Watchable], Seq[Watchable])] =
      if (skipSelectiveExecution) None
      else
        prevCommandState.finalFrame
          .filter(f =>
            f.depth == depth && f.tasksAndParams == tasksAndParams
          )
          .map(f => (f.evalWatched, f.moduleWatched))
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
    evaled match {
      case f: Result.Failure =>
        withFinal.withError(mill.internal.Util.formatError(f, logger.prompt.errorColor))
      case _ => withFinal
    }
  }

}

object MillBuildBootstrap {

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
