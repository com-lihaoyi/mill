package mill.daemon

import mill.api.daemon.internal.{
  BuildFileApi,
  CompileProblemReporter,
  EvaluatorApi,
  MillScalaParser,
  PathRefApi,
  RootModuleApi
}
import mill.api.{Logger, Result, SystemStreams, Val}
import mill.constants.CodeGenConstants.*
import mill.constants.OutFiles.{millBuild, millRunnerState}
import mill.api.daemon.Watchable
import mill.api.internal.RootModule
import mill.api.{BuildCtx, PathRef, SelectMode}
import mill.internal.PrefixLogger
import mill.meta.{FileImportGraph, MillBuildRootModule}
import mill.meta.CliImports
import mill.server.Server
import mill.util.BuildInfo

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
 * each evaluation is cached in-memory in [[prevRunnerState]].
 *
 * When a subsequent evaluation happens, each level of [[evaluateRec]] uses
 * its corresponding frame from [[prevRunnerState]] to avoid work, re-using
 * classloaders or workers to avoid running expensive classloading or
 * re-evaluation. This should be transparent, improving performance without
 * affecting behavior.
 */
class MillBuildBootstrap(
    projectRoot: os.Path,
    output: os.Path,
    keepGoing: Boolean,
    imports: Seq[String],
    env: Map[String, String],
    ec: Option[ThreadPoolExecutor],
    tasksAndParams: Seq[String],
    prevRunnerState: RunnerState,
    logger: Logger,
    needBuildFile: Boolean,
    requestedMetaLevel: Option[Int],
    allowPositionalCommandArgs: Boolean,
    systemExit: Server.StopServer,
    streams0: SystemStreams,
    selectiveExecution: Boolean,
    offline: Boolean,
    reporter: EvaluatorApi => Int => Option[CompileProblemReporter]
) { outer =>
  import MillBuildBootstrap.*

  val millBootClasspath: Seq[os.Path] = prepareMillBootClasspath(output)
  val millBootClasspathPathRefs: Seq[PathRef] = millBootClasspath.map(PathRef(_, quick = true))

  def evaluate(): Watching.Result[RunnerState] = CliImports.withValue(imports) {
    val runnerState = evaluateRec(0)

    for ((frame, depth) <- runnerState.frames.zipWithIndex) {
      os.write.over(
        recOut(output, depth) / millRunnerState,
        upickle.write(frame.loggedData, indent = 4),
        createFolders = true
      )
    }

    Watching.Result(
      watched = runnerState.watched,
      error = runnerState.errorOpt,
      result = runnerState
    )
  }

  def evaluateRec(depth: Int): RunnerState = {
    logger.withChromeProfile(s"meta-level $depth") {
      // println(s"+evaluateRec($depth) " + recRoot(projectRoot, depth))
      val currentRoot = recRoot(projectRoot, depth)
      val prevFrameOpt = prevRunnerState.frames.lift(depth)
      val prevOuterFrameOpt = prevRunnerState.frames.lift(depth - 1)

      val requestedDepth = requestedMetaLevel.filter(_ >= 0).getOrElse(0)

      val currentRootContainsBuildFile = rootBuildFileNames.asScala.exists(rootBuildFileName =>
        os.exists(currentRoot / rootBuildFileName)
      )

      val (nestedState, headerDataOpt) =
        if (depth == 0) {
          // On this level we typically want to assume a Mill project, which means we want to require an existing `build.mill`.
          // Unfortunately, some tasks also make sense without a `build.mill`, e.g. the `init` command.
          // Hence, we only report a missing `build.mill` as a problem if the command itself does not succeed.
          lazy val state = evaluateRec(depth + 1)
          if (currentRootContainsBuildFile) (state, None)
          else {
            val msg =
              s"No build file (${rootBuildFileNames.asScala.mkString(", ")}) found in $projectRoot. Are you in a Mill project directory?"
            val res =
              if (needBuildFile) RunnerState(None, Nil, Some(msg), None)
              else {
                state match {
                  case RunnerState(bootstrapModuleOpt, frames, Some(error), None) =>
                    // Add a potential clue (missing build.mill) to the underlying error message
                    RunnerState(bootstrapModuleOpt, frames, Some(msg + "\n" + error))
                  case state => state
                }
              }
            (res, None)
          }
        } else {
          val parsedScriptFiles = FileImportGraph
            .parseBuildFiles(
              projectRoot,
              currentRoot / os.up,
              output,
              MillScalaParser.current.value
            )

          val state =
            if (currentRootContainsBuildFile) evaluateRec(depth + 1)
            else {
              val bootstrapModule =
                new MillBuildRootModule.BootstrapModule()(
                  using new RootModule.Info(currentRoot, output, projectRoot)
                )
              RunnerState(Some(bootstrapModule), Nil, None, Some(parsedScriptFiles.buildFile))
            }

          (state, Some(parsedScriptFiles.headerData))
        }

      val classloaderChanged =
        prevRunnerState.frames.lift(depth + 1).flatMap(_.classLoaderOpt) !=
          nestedState.frames.headOption.flatMap(_.classLoaderOpt)

      // If the classloader changed, it means the old classloader was closed
      // and all workers were closed as well, so we return an empty workerCache
      // for the next evaluation
      val newWorkerCache =
        if (classloaderChanged) Map.empty
        else prevFrameOpt.map(_.workerCache).getOrElse(Map.empty)

      val res =
        if (nestedState.errorOpt.isDefined) nestedState.add(errorOpt = nestedState.errorOpt)
        else if (depth == 0 && requestedDepth > nestedState.frames.size) {
          // User has requested a frame depth, we actually don't have
          nestedState.add(errorOpt =
            Some(
              s"Invalid selected meta-level ${requestedDepth}. Valid range: 0 .. ${nestedState.frames.size}"
            )
          )
        } else if (depth < requestedDepth) {
          // We already evaluated on a deeper level, hence we just need to make sure,
          // we return a proper structure with all already existing watch data
          val evalState = RunnerState.Frame(
            newWorkerCache,
            Seq.empty,
            Seq.empty,
            Map.empty,
            None,
            Nil,
            // We don't want to evaluate anything in this depth (and above), so we just skip creating an evaluator,
            // mainly because we didn't even construct (compile) its classpath
            None,
            None
          )
          nestedState.add(frame = evalState, errorOpt = None)
        } else {
          val rootModuleRes = nestedState.frames.headOption match {
            case None =>
              Result.Success(BuildFileApi.Bootstrap(nestedState.bootstrapModuleOpt.get))
            case Some(nestedFrame) => getRootModule(nestedFrame.classLoaderOpt.get)
          }

          rootModuleRes match {
            case Result.Failure(err) => nestedState.add(errorOpt = Some(err))
            case Result.Success((buildFileApi)) =>

              Using.resource(makeEvaluator(
                projectRoot,
                output,
                keepGoing,
                env,
                logger,
                ec,
                allowPositionalCommandArgs,
                systemExit,
                streams0,
                selectiveExecution,
                offline,
                newWorkerCache,
                nestedState.frames.headOption.map(_.codeSignatures).getOrElse(Map.empty),
                buildFileApi.rootModule,
                // We want to use the grandparent buildHash, rather than the parent
                // buildHash, because the parent build changes are instead detected
                // by analyzing the scriptImportGraph in a more fine-grained manner.
                nestedState
                  .frames
                  .dropRight(1)
                  .headOption
                  .map(_.runClasspath)
                  .getOrElse(millBootClasspathPathRefs)
                  .map(p => (os.Path(p.javaPath), p.sig))
                  .hashCode(),
                nestedState
                  .frames
                  .headOption
                  .flatMap(_.classLoaderOpt)
                  .map(_.hashCode())
                  .getOrElse(0),
                depth,
                actualBuildFileName = nestedState.buildFile,
                headerData = headerDataOpt.getOrElse("")
              )) { evaluator =>
                if (depth == requestedDepth) {
                  processFinalTasks(nestedState, buildFileApi, evaluator)
                } else if (depth <= requestedDepth) nestedState
                else {
                  processRunClasspath(
                    nestedState,
                    buildFileApi,
                    evaluator,
                    prevFrameOpt,
                    prevOuterFrameOpt,
                    depth
                  )
                }
              }
          }
        }

      res
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
      prevFrameOpt: Option[RunnerState.Frame],
      prevOuterFrameOpt: Option[RunnerState.Frame],
      depth: Int
  ): RunnerState = {
    evaluateWithWatches(
      buildFileApi,
      evaluator,
      Seq("millBuildRootModuleResult"),
      selectiveExecution = false,
      reporter = reporter(evaluator)
    ) match {
      case (Result.Failure(error), evalWatches, moduleWatches) =>
        val evalState = RunnerState.Frame(
          evaluator.workerCache.toMap,
          evalWatches,
          moduleWatches,
          Map.empty,
          None,
          Nil,
          None,
          Option(evaluator)
        )

        nestedState.add(frame = evalState, errorOpt = Some(error))

      case (
            Result.Success(Seq(Tuple3(
              runClasspath: Seq[PathRefApi],
              compileClasses: PathRefApi,
              codeSignatures: Map[String, Int]
            ))),
            evalWatches,
            moduleWatches
          ) =>
        val runClasspathChanged = !prevFrameOpt.exists(
          _.runClasspath.map(_.sig).sum == runClasspath.map(_.sig).sum
        )

        // handling module watching is a bit weird; we need to know whether
        // to create a new classloader immediately after the `runClasspath`
        // is compiled, but we only know what the respective `moduleWatched`
        // contains after the evaluation on this classloader has executed, which
        // happens one level up in the recursion. Thus, to check whether
        // `moduleWatched` needs us to re-create the classloader, we have to
        // look at the `moduleWatched` of one frame up (`prevOuterFrameOpt`),
        // and not the `moduleWatched` from the current frame (`prevFrameOpt`)
        val moduleWatchChanged = prevOuterFrameOpt
          .exists(_.moduleWatched.exists(w => !Watching.haveNotChanged(w)))

        val classLoader = if (runClasspathChanged || moduleWatchChanged) {
          // Make sure we close the old classloader every time we create a new
          // one, to avoid memory leaks, as well as all the workers in each subsequent
          // frame's `workerCache`s that may depend on classes loaded by that classloader

          prevRunnerState.frames.lift(depth - 1).foreach(
            _.workerCache.collect { case (_, (_, Val(v: AutoCloseable))) => v.close() }
          )

          prevFrameOpt.foreach(_.classLoaderOpt.foreach(_.close()))
          val cl = mill.util.Jvm.createClassLoader(
            runClasspath.map(p => os.Path(p.javaPath)),
            null,
            sharedLoader = classOf[MillBuildBootstrap].getClassLoader,
            sharedPrefixes = Seq("java.", "javax.", "scala.", "mill.api.daemon", "sbt.testing.")
          )
          cl
        } else {
          prevFrameOpt.get.classLoaderOpt.get
        }

        val evalState = RunnerState.Frame(
          evaluator.workerCache.toMap,
          evalWatches,
          moduleWatches,
          codeSignatures,
          Some(classLoader),
          runClasspath,
          Some(compileClasses),
          Option(evaluator)
        )

        nestedState.add(frame = evalState)

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
      evaluator: EvaluatorApi
  ): RunnerState = {

    assert(nestedState.frames.forall(_.evaluator.isDefined))

    val (evaled, evalWatched, moduleWatches) = evaluateWithWatches(
      buildFileApi,
      evaluator,
      tasksAndParams,
      selectiveExecution,
      reporter = reporter(evaluator)
    )
    val evalState = RunnerState.Frame(
      evaluator.workerCache.toMap,
      evalWatched,
      moduleWatches,
      Map.empty,
      None,
      Nil,
      None,
      Option(evaluator)
    )

    nestedState.add(frame = evalState, errorOpt = evaled.toEither.left.toOption)
  }

}

object MillBuildBootstrap {
  // Keep this outside of `case class MillBuildBootstrap` because otherwise the lambdas
  // tend to capture the entire enclosing instance, causing memory leaks
  def makeEvaluator(
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
      workerCache: Map[String, (Int, Val)],
      codeSignatures: Map[String, Int],
      rootModule: RootModuleApi,
      millClassloaderSigHash: Int,
      millClassloaderIdentityHash: Int,
      depth: Int,
      actualBuildFileName: Option[String] = None,
      headerData: String
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
    lazy val evaluator: EvaluatorApi = evalImplCls.getConstructors.head.newInstance(
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
        workerCache.to(collection.mutable.Map),
        env,
        !keepGoing,
        ec,
        codeSignatures,
        (reason: String, exitCode: Int) => systemExit(reason, exitCode),
        streams0,
        () => evaluator,
        offline,
        headerData
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
    import buildFileApi._
    evalWatchedValues.clear()
    val evalTaskResult = evaluator.evaluate(
      tasksAndParams,
      SelectMode.Separated,
      reporter = reporter,
      selectiveExecution = selectiveExecution
    )

    evalTaskResult match {
      case Result.Failure(msg) => (Result.Failure(msg), Nil, moduleWatchedValues)
      case Result.Success(res: EvaluatorApi.Result[Any]) =>
        res.values match {
          case Result.Failure(msg) =>
            (Result.Failure(msg), res.watchable ++ evalWatchedValues, moduleWatchedValues)
          case Result.Success(results) =>
            (Result.Success(results), res.watchable ++ evalWatchedValues, moduleWatchedValues)
        }
    }
  }

  def getRootModule(runClassLoader: URLClassLoader)
      : Result[BuildFileApi] = {
    val buildClass = runClassLoader.loadClass(s"$globalPackagePrefix.BuildFileImpl")

    val valueMethod = buildClass.getMethod("value")
    mill.api.ExecResult.catchWrapException {
      valueMethod.invoke(null).asInstanceOf[BuildFileApi]
    }
  }

  def recRoot(projectRoot: os.Path, depth: Int): os.Path = {
    projectRoot / Seq.fill(depth)(millBuild)
  }

  def recOut(output: os.Path, depth: Int): os.Path = {
    output / Seq.fill(depth)(millBuild)
  }

}
