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
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.Using
import scala.collection.mutable.Buffer

/**
 * Logic around bootstrapping Mill, creating a [[MillBuildRootModule.BootstrapModule]]
 * and compiling builds/meta-builds and classloading their [[RootModule]]s so we
 * can evaluate the requested tasks on the [[RootModule]] representing the user's
 * `build.mill` file.
 *
 * When Mill is run in client-server mode, or with `--watch`, then data from
 * each evaluation is cached in-memory in [[prevCommandState]] and published
 * reusable meta-build frames are shared via [[snapshotPublishedState]].
 *
 * When a subsequent evaluation happens, each level of [[evaluateRec]] uses
 * its corresponding frame from prior state to avoid work, re-using
 * classloaders or workers to avoid running expensive classloading or
 * re-evaluation. This should be transparent, improving performance without
 * affecting behavior.
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
    reporter: EvaluatorApi => Int => Option[CompileProblemReporter],
    enableTicker: Boolean,
    snapshotPublishedState: () => RunnerState.ReusableSnapshot,
    publishReusableState: (Int, Seq[RunnerState.Frame]) => Unit
) { outer =>
  import MillBuildBootstrap.*

  val millBootClasspath: Seq[os.Path] = prepareMillBootClasspath(output)
  val millBootClasspathPathRefs: Seq[PathRef] = millBootClasspath.map(PathRef(_, quick = true))

  def evaluate(): RunnerState = CliImports.withValue(imports) {
    val runnerState = evaluateRec(0)

    for ((frame, depth) <- runnerState.frames.zipWithIndex) {
      os.write.over(
        recOut(output, depth) / millRunnerState,
        upickle.write(frame.loggedData, indent = 4),
        createFolders = true
      )
    }

    runnerState
  }

  def evaluateRec(depth: Int): RunnerState = logger.withChromeProfile(s"meta-level $depth") {
    // println(s"+evaluateRec($depth) " + recRoot(projectRoot, depth))
    val currentRoot = recRoot(topLevelProjectRoot, depth)

    val nestedState =
      if (containsBuildFile(currentRoot)) evaluateRec(depth + 1)
      else makeBootstrapState(currentRoot)

    val nestedFrames = nestedState.frames

    val requestedDepth = computeRequestedDepth(requestedMetaLevel, depth, nestedFrames.size)

    // If an earlier frame errored out, just propagate the error to this frame
    if (nestedState.errorOpt.isDefined) nestedState.add(errorOpt = nestedState.errorOpt)
    else if (depth == 0 && (requestedDepth > nestedFrames.size || requestedDepth < 0)) {
      // User has requested a frame depth, we actually don't have
      nestedState.add(errorOpt = Some(invalidLevelMsg(requestedMetaLevel, nestedFrames.size)))
    } else if (nestedFrames.headOption.exists(_.classLoaderOpt.isEmpty)) {
      // Skip this depth if final tasks already ran at a deeper level due to `--meta-level`
      // or `@nonBootstrapped`, and `classLoaderOpt` is empty
      nestedState.add(frame = RunnerState.Frame.empty)
    } else {
      val rootModuleRes = nestedFrames.headOption match {
        case None => Result.Success(BuildFileApi.Bootstrap(nestedState.bootstrapModuleOpt.get))
        case Some(nestedFrame) => getRootModule(nestedFrame.classLoaderOpt.get)
      }
      evaluateResolvedRootModule(rootModuleRes, nestedState, depth, requestedDepth)
    }
  }

  private def evaluateResolvedRootModule(
      rootModuleRes: Result[BuildFileApi],
      nestedState: RunnerState,
      depth: Int,
      requestedDepth: Int
  ): RunnerState = {
    rootModuleRes match {
    case f: Result.Failure =>
      nestedState.add(errorOpt = Some(Util.formatError(f, logger.prompt.errorColor)))

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
          case Result.Success(true) => processFinalTasks(nestedState, buildFileApi, evaluator)

          // For both Success(false) and Failure, proceed with normal evaluation.
          // If areAllNonBootstrapped failed (e.g., task doesn't exist), the actual
          // evaluation will also fail, but moduleWatched will be properly captured.
          case _ =>
            if (depth > requestedDepth) {
              processRunClasspath(nestedState, buildFileApi, evaluator, depth)
            } else if (depth == requestedDepth) {
              processFinalTasks(nestedState, buildFileApi, evaluator)
            } else ??? // should be handled by outer conditional
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

    RunnerState(mod, Nil, error, Some(foundRootBuildFileName), Seq(bootstrapEvalWatched))
  }

  def makeEvaluator(
      nestedState: RunnerState,
      rootModule: RootModuleApi,
      depth: Int
  ): EvaluatorApi = {
    val currentRoot = recRoot(topLevelProjectRoot, depth)
    val staticBuildOverrides0 = tryReadParent(currentRoot, "build.mill.yaml")
      .orElse(tryReadParent(currentRoot, "build.mill"))

    val staticBuildOverrideFiles =
      staticBuildOverrides0.toSeq ++
        nestedState.frames.headOption.fold(Map())(_.buildOverrideFiles)

    val millClassloaderIdentityHash0 = nestedState
      .frames
      .headOption
      .flatMap(_.classLoaderOpt)
      .map(_.hashCode())
      .getOrElse(0)

    // Use the process-level shared worker cache. Workers are thread-safe and
    // can be shared across concurrent commands. SharedWorkerCache.forDepth
    // handles classloader changes automatically (closes stale workers).
    val workerCache =
      SharedWorkerCache.forDepth(depth, millClassloaderIdentityHash0)

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
      codeSignatures = nestedState.frames.headOption.map(_.codeSignatures).getOrElse(Map.empty),
      // Pass spanning tree from the frame - only populated when classloader changed
      spanningInvalidationTree = nestedState.frames.headOption.flatMap(_.spanningInvalidationTree),
      rootModule = rootModule,
      // Use the current frame's runClasspath (includes mvnDeps and Mill jars) but filter out
      // compile.dest and generatedScriptSources.dest since build code changes are handled
      // by codesig analysis, not by classLoaderSigHash.
      millClassloaderSigHash = nestedState.frames.headOption match {
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
        val evalState = RunnerState.Frame(
          workerCache = evaluator.workerCache.toMap,
          evalWatched = evalWatches,
          moduleWatched = moduleWatches,
          codeSignatures = Map.empty,
          classLoaderOpt = None,
          metaBuildReadLeaseOpt = None,
          runClasspath = Nil,
          compileOutput = None,
          evaluator = Option(evaluator),
          buildOverrideFiles = Map(),
          spanningInvalidationTree = None
        )

        nestedState.add(
          frame = evalState,
          errorOpt = Some(mill.internal.Util.formatError(f, logger.prompt.errorColor))
        )

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

        // Check whether the published meta-build state at this depth needs a classloader refresh.
        // Compares the compiled runClasspath against the published frame's classpath and checks
        // whether any module-watched files in the outer frame have changed.
        def needsClassloaderRefresh(
            publishedFrameOpt: Option[RunnerState.Frame],
            publishedOuterFrameOpt: Option[RunnerState.Frame]
        ): Boolean = {
          val runClasspathChanged = !publishedFrameOpt.exists(
            _.runClasspath.map(_.sig).sum == runClasspath.map(_.sig).sum
          )
          // handling module watching is a bit weird; we need to know whether
          // to create a new classloader immediately after the `runClasspath`
          // is compiled, but we only know what the respective `moduleWatched`
          // contains after the evaluation on this classloader has executed, which
          // happens one level up in the recursion. Thus, to check whether
          // `moduleWatched` needs us to re-create the classloader, we have to
          // look at the `moduleWatched` of one frame up (`publishedOuterFrameOpt`),
          // and not the `moduleWatched` from the current frame (`publishedFrameOpt`)
          val moduleWatchChanged = publishedOuterFrameOpt
            .exists(_.moduleWatched.exists(w => !Watching.haveNotChanged(w)))
          runClasspathChanged || moduleWatchChanged
        }

        def snapshotFrames(): (Option[RunnerState.Frame], Option[RunnerState.Frame]) = {
          val snapshot = snapshotPublishedState()
          val frameOpt = snapshot.frame(depth).orElse(prevCommandState.frames.lift(depth))
          val outerFrameOpt =
            snapshot.frame(depth - 1).orElse(prevCommandState.frames.lift(depth - 1))
          (frameOpt, outerFrameOpt)
        }

        def nextStateFor(
            classLoader: mill.api.MillURLClassLoader,
            metaBuildReadLeaseOpt: Option[WorkspaceLocking.Lease]
        ) = {
          val (publishedFrameOpt, publishedOuterFrameOpt) = snapshotFrames()
          val needsUpdate = needsClassloaderRefresh(publishedFrameOpt, publishedOuterFrameOpt)

          val evalState = RunnerState.Frame(
            workerCache = evaluator.workerCache.toMap,
            evalWatched = evalWatches,
            moduleWatched = moduleWatches,
            codeSignatures = codeSignatures,
            classLoaderOpt = Some(classLoader),
            metaBuildReadLeaseOpt = metaBuildReadLeaseOpt,
            runClasspath = runClasspath,
            compileOutput = Some(compileClasses),
            evaluator = Option(evaluator),
            buildOverrideFiles = buildOverrideFiles,
            // Only pass the spanning tree when the published meta-build state changed
            spanningInvalidationTree = Option.when(needsUpdate)(spanningInvalidationTree)
          )
          nestedState.add(frame = evalState)
        }

        val initialReadLease = workspaceLockManager.acquireMetaBuildRead(depth)
        var initialReadLeaseClosed = false
        try {
          val (currentFrameOpt, currentOuterFrameOpt) = snapshotFrames()
          val canReusePublished =
            currentFrameOpt.exists(_.classLoaderOpt.isDefined) &&
              !needsClassloaderRefresh(currentFrameOpt, currentOuterFrameOpt)

          if (canReusePublished) {
            nextStateFor(currentFrameOpt.get.classLoaderOpt.get, Some(initialReadLease))
          } else {
            initialReadLease.close()
            initialReadLeaseClosed = true
            // Use explicit write lease so we can downgrade to read before returning.
            // We cannot call acquireMetaBuildRead inside a write lock because the
            // semaphore-based lock is not reentrant (unlike ReentrantReadWriteLock).
            val writeLease = workspaceLockManager.acquireMetaBuildWrite(depth)
            try {
              val (latestFrameOpt, latestOuterFrameOpt) = snapshotFrames()
              val needsUpdate = needsClassloaderRefresh(latestFrameOpt, latestOuterFrameOpt)

              if (!needsUpdate && latestFrameOpt.exists(_.classLoaderOpt.isDefined)) {
                val readLease = writeLease.downgradeToRead()
                nextStateFor(latestFrameOpt.get.classLoaderOpt.get, Some(readLease))
              } else {
                // Close old classloaders to avoid memory leaks. Workers at this depth
                // are handled by SharedWorkerCache.forDepth (called in makeEvaluator),
                // which detects the classloader change and closes stale workers.
                val previousClassLoaders =
                  Seq(
                    prevCommandState.frames.lift(depth).flatMap(_.classLoaderOpt),
                    snapshotPublishedState().frame(depth).flatMap(_.classLoaderOpt)
                  ).flatten.distinct
                previousClassLoaders.foreach(_.close())
                val nextState = nextStateFor(createClassLoader(), None)
                publishReusableState(depth, nextState.frames)
                val readLease = writeLease.downgradeToRead()
                nextState.copy(
                  frames = nextState.frames.updated(
                    0,
                    nextState.frames.head.copy(metaBuildReadLeaseOpt = Some(readLease))
                  )
                )
              }
            } catch {
              case e: Throwable =>
                try writeLease.close()
                catch { case _: Throwable => }
                throw e
            }
          }
        } catch {
          case e: Throwable =>
            if (!initialReadLeaseClosed) {
              try initialReadLease.close()
              catch { case _: Throwable => }
            }
            throw e
        }

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
      evaluator0: EvaluatorApi
  ): RunnerState = {
    assert(nestedState.frames.forall(_.evaluator.isDefined))

    val evaluator = evaluator0.withIsFinalDepth(true)
    val (evaled, evalWatched, moduleWatches) = evaluateWithWatches(
      buildFileApi = buildFileApi,
      evaluator = evaluator,
      tasksAndParams = tasksAndParams,
      selectiveExecution = selectiveExecution,
      reporter = reporter(evaluator)
    )

    val evalState = RunnerState.Frame(
      workerCache = evaluator.workerCache.toMap,
      evalWatched = evalWatched,
      moduleWatched = moduleWatches,
      codeSignatures = Map.empty,
      classLoaderOpt = None,
      metaBuildReadLeaseOpt = None,
      runClasspath = Nil,
      compileOutput = None,
      evaluator = Option(evaluator),
      buildOverrideFiles = Map(),
      spanningInvalidationTree = None
    )

    nestedState.add(
      frame = evalState,
      errorOpt = evaled match {
        case f: Result.Failure => Some(mill.internal.Util.formatError(f, logger.prompt.errorColor))
        case _ => None
      }
    )
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
