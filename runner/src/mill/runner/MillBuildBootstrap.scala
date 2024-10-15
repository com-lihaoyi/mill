package mill.runner

import mill.given
import mill.util.{ColorLogger, PrefixLogger, Watchable}
import mill.main.BuildInfo
import mill.main.client.CodeGenConstants._
import mill.api.{PathRef, SystemStreams, Val, internal}
import mill.eval.Evaluator
import mill.main.RunScript
import mill.resolve.SelectMode
import mill.define.{BaseModule, Discover, Segments}
import mill.main.client.OutFiles.{millBuild, millRunnerState}
import mill.runner.worker.api.MillScalaParser
import mill.runner.worker.ScalaCompilerWorker

import java.net.URLClassLoader

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
@internal
class MillBuildBootstrap(
    projectRoot: os.Path,
    output: os.Path,
    home: os.Path,
    keepGoing: Boolean,
    imports: Seq[String],
    env: Map[String, String],
    threadCount: Option[Int],
    targetsAndParams: Seq[String],
    prevRunnerState: RunnerState,
    logger: ColorLogger,
    disableCallgraph: Boolean,
    needBuildSc: Boolean,
    requestedMetaLevel: Option[Int],
    allowPositionalCommandArgs: Boolean,
    systemExit: Int => Nothing,
    streams0: SystemStreams,
    scalaCompilerWorker: ScalaCompilerWorker.ResolvedWorker
) { outer =>
  import MillBuildBootstrap._

  val millBootClasspath: Seq[os.Path] = prepareMillBootClasspath(output)
  val millBootClasspathPathRefs: Seq[PathRef] = millBootClasspath.map(PathRef(_, quick = true))

  def parserBridge: MillScalaParser = {
    scalaCompilerWorker.worker
  }

  def evaluate(): Watching.Result[RunnerState] = CliImports.withValue(imports) {
    val runnerState = evaluateRec(0)(using parserBridge)

    for ((frame, depth) <- runnerState.frames.zipWithIndex) {
      os.write.over(
        recOut(output, depth) / millRunnerState,
        upickle.default.write(frame.loggedData, indent = 4),
        createFolders = true
      )
    }

    Watching.Result(
      watched = runnerState.frames.flatMap(f => f.evalWatched ++ f.moduleWatched),
      error = runnerState.errorOpt,
      result = runnerState
    )
  }

  def evaluateRec(depth: Int)(using parser: MillScalaParser): RunnerState = {
    // println(s"+evaluateRec($depth) " + recRoot(projectRoot, depth))
    val prevFrameOpt = prevRunnerState.frames.lift(depth)
    val prevOuterFrameOpt = prevRunnerState.frames.lift(depth - 1)

    val requestedDepth = requestedMetaLevel.filter(_ >= 0).getOrElse(0)

    val nestedState: RunnerState =
      if (depth == 0) {
        // On this level we typically want assume a Mill project, which means we want to require an existing `build.mill`.
        // Unfortunately, some targets also make sense without a `build.mill`, e.g. the `init` command.
        // Hence we only report a missing `build.mill` as an problem if the command itself does not succeed.
        lazy val state = evaluateRec(depth + 1)
        if (
          rootBuildFileNames.exists(rootBuildFileName =>
            os.exists(recRoot(projectRoot, depth) / rootBuildFileName)
          )
        ) state
        else {
          val msg =
            s"${rootBuildFileNames.head} file not found in $projectRoot. Are you in a Mill project folder?"
          if (needBuildSc) {
            RunnerState(None, Nil, Some(msg))
          } else {
            state match {
              case RunnerState(bootstrapModuleOpt, frames, Some(error)) =>
                // Add a potential clue (missing build.mill) to the underlying error message
                RunnerState(bootstrapModuleOpt, frames, Some(msg + "\n" + error))
              case state => state
            }
          }
        }
      } else {
        val parsedScriptFiles = FileImportGraph.parseBuildFiles(
          parser,
          projectRoot,
          recRoot(projectRoot, depth) / os.up,
          output
        )

        if (parsedScriptFiles.millImport) evaluateRec(depth + 1)
        else {
          val bootstrapModule =
            new MillBuildRootModule.BootstrapModule(
              projectRoot,
              recRoot(projectRoot, depth),
              output,
              millBootClasspath,
              scalaCompilerWorker
            )(
              mill.main.RootModule.Info(
                recRoot(projectRoot, depth),
                Discover[MillBuildRootModule.BootstrapModule]
              )
            )
          RunnerState(Some(bootstrapModule), Nil, None)
        }
      }

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
          prevFrameOpt.map(_.workerCache).getOrElse(Map.empty),
          Seq.empty,
          Seq.empty,
          Map.empty,
          None,
          Nil,
          // We don't want to evaluate anything in this depth (and above), so we just skip creating an evaluator,
          // mainly because we didn't even constructed (compiled) it's classpath
          None,
          None
        )
        nestedState.add(frame = evalState, errorOpt = None)
      } else {
        val rootModule = nestedState.frames.headOption match {
          case None => nestedState.bootstrapModuleOpt.get
          case Some(nestedFrame) => getRootModule(nestedFrame.classLoaderOpt.get)
        }

        val evaluator = makeEvaluator(
          prevFrameOpt.map(_.workerCache).getOrElse(Map.empty),
          nestedState.frames.headOption.map(_.methodCodeHashSignatures).getOrElse(Map.empty),
          rootModule,
          // We want to use the grandparent buildHash, rather than the parent
          // buildHash, because the parent build changes are instead detected
          // by analyzing the scriptImportGraph in a more fine-grained manner.
          nestedState
            .frames
            .dropRight(1)
            .headOption
            .map(_.runClasspath)
            .getOrElse(millBootClasspathPathRefs)
            .map(p => (p.path, p.sig))
            .hashCode(),
          nestedState
            .frames
            .headOption
            .flatMap(_.classLoaderOpt)
            .map(_.hashCode())
            .getOrElse(0),
          depth
        )

        if (depth != 0) {
          val retState = processRunClasspath(
            nestedState,
            rootModule,
            evaluator,
            prevFrameOpt,
            prevOuterFrameOpt
          )

          if (retState.errorOpt.isEmpty && depth == requestedDepth) {
            // TODO: print some message and indicate actual evaluated frame
            val evalRet = processFinalTargets(nestedState, rootModule, evaluator)
            if (evalRet.errorOpt.isEmpty) retState
            else evalRet
          } else
            retState

        } else {
          processFinalTargets(nestedState, rootModule, evaluator)
        }

      }
    // println(s"-evaluateRec($depth) " + recRoot(projectRoot, depth))
    res
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
      rootModule: BaseModule,
      evaluator: Evaluator,
      prevFrameOpt: Option[RunnerState.Frame],
      prevOuterFrameOpt: Option[RunnerState.Frame]
  ): RunnerState = {
    evaluateWithWatches(
      rootModule,
      evaluator,
      Seq("{runClasspath,compile,methodCodeHashSignatures}")
    ) match {
      case (Left(error), evalWatches, moduleWatches) =>
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
            Right(Seq(
              Val(runClasspath: Seq[PathRef]),
              Val(compile: mill.scalalib.api.CompilationResult),
              Val(methodCodeHashSignatures: Map[String, Int])
            )),
            evalWatches,
            moduleWatches
          ) =>
        val runClasspathChanged = !prevFrameOpt.exists(
          _.runClasspath.map(_.sig).sum == runClasspath.map(_.sig).sum
        )

        // handling module watching is a bit weird; we need to know whether or
        // not to create a new classloader immediately after the `runClasspath`
        // is compiled, but we only know what the respective `moduleWatched`
        // contains after the evaluation on this classloader has executed, which
        // happens one level up in the recursion. Thus to check whether
        // `moduleWatched` needs us to re-create the classloader, we have to
        // look at the `moduleWatched` of one frame up (`prevOuterFrameOpt`),
        // and not the `moduleWatched` from the current frame (`prevFrameOpt`)
        val moduleWatchChanged =
          prevOuterFrameOpt.exists(_.moduleWatched.exists(!_.validate()))

        val classLoader = if (runClasspathChanged || moduleWatchChanged) {
          // Make sure we close the old classloader every time we create a new
          // one, to avoid memory leaks
          prevFrameOpt.foreach(_.classLoaderOpt.foreach(_.close()))
          val cl = new RunnerState.URLClassLoader(
            runClasspath.map(_.path.toNIO.toUri.toURL).toArray,
            getClass.getClassLoader
          )
          cl
        } else {
          prevFrameOpt.get.classLoaderOpt.get
        }

        val evalState = RunnerState.Frame(
          evaluator.workerCache.toMap,
          evalWatches,
          moduleWatches,
          methodCodeHashSignatures,
          Some(classLoader),
          runClasspath,
          Some(compile.classes),
          Option(evaluator)
        )

        nestedState.add(frame = evalState)
    }
  }

  /**
   * Handles the final evaluation of the user-provided targets. Since there are
   * no further levels to evaluate, we do not need to save a `scriptImportGraph`,
   * classloader, or runClasspath.
   */
  def processFinalTargets(
      nestedState: RunnerState,
      rootModule: BaseModule,
      evaluator: Evaluator
  ): RunnerState = {

    assert(nestedState.frames.forall(_.evaluator.isDefined))

    val (evaled, evalWatched, moduleWatches) = Evaluator.allBootstrapEvaluators.withValue(
      Evaluator.AllBootstrapEvaluators(Seq(evaluator) ++ nestedState.frames.flatMap(_.evaluator))
    ) {
      evaluateWithWatches(rootModule, evaluator, targetsAndParams)
    }

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

    nestedState.add(frame = evalState, errorOpt = evaled.left.toOption)
  }

  def makeEvaluator(
      workerCache: Map[Segments, (Int, Val)],
      methodCodeHashSignatures: Map[String, Int],
      rootModule: BaseModule,
      millClassloaderSigHash: Int,
      millClassloaderIdentityHash: Int,
      depth: Int
  ): Evaluator = {

    val bootLogPrefix: Seq[String] =
      if (depth == 0) Nil
      else Seq((Seq.fill(depth - 1)(millBuild) ++ Seq("build.mill")).mkString("/"))

    mill.eval.EvaluatorImpl(
      home,
      projectRoot,
      recOut(output, depth),
      recOut(output, depth),
      rootModule,
      new PrefixLogger(logger, bootLogPrefix),
      classLoaderSigHash = millClassloaderSigHash,
      classLoaderIdentityHash = millClassloaderIdentityHash,
      workerCache = workerCache.to(collection.mutable.Map),
      env = env,
      failFast = !keepGoing,
      threadCount = threadCount,
      methodCodeHashSignatures = methodCodeHashSignatures,
      disableCallgraph = disableCallgraph,
      allowPositionalCommandArgs = allowPositionalCommandArgs,
      systemExit = systemExit,
      exclusiveSystemStreams = streams0
    )
  }

}

@internal
object MillBuildBootstrap {
  def prepareMillBootClasspath(millBuildBase: os.Path): Seq[os.Path] = {
    val enclosingClasspath: Seq[os.Path] = mill.util.Classpath.classpath(getClass.getClassLoader)

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
      rootModule: BaseModule,
      evaluator: Evaluator,
      targetsAndParams: Seq[String]
  ): (Either[String, Seq[Any]], Seq[Watchable], Seq[Watchable]) = {
    rootModule.evalWatchedValues.clear()
    val evalTaskResult =
      RunScript.evaluateTasksNamed(evaluator, targetsAndParams, SelectMode.Separated)
    val moduleWatched = rootModule.watchedValues.toVector
    val addedEvalWatched = rootModule.evalWatchedValues.toVector

    evalTaskResult match {
      case Left(msg) => (Left(msg), Nil, moduleWatched)
      case Right((watched, evaluated)) =>
        evaluated match {
          case Left(msg) => (Left(msg), watched ++ addedEvalWatched, moduleWatched)
          case Right(results) =>
            (Right(results.map(_._1)), watched ++ addedEvalWatched, moduleWatched)
        }
    }
  }

  def getRootModule(runClassLoader: URLClassLoader): BaseModule = {
    val buildClass = runClassLoader.loadClass(s"$globalPackagePrefix.${wrapperObjectName}$$")
    buildClass.getField("MODULE$").get(buildClass).asInstanceOf[BaseModule]
  }

  def recRoot(projectRoot: os.Path, depth: Int): os.Path = {
    projectRoot / Seq.fill(depth)(millBuild)
  }

  def recOut(output: os.Path, depth: Int): os.Path = {
    output / Seq.fill(depth)(millBuild)
  }

}
