package mill.runner
import mill.util.{ColorLogger, PrefixLogger, Util, Watchable}
import mill.{BuildInfo, T}
import mill.api.{PathRef, internal}
import mill.eval.Evaluator
import mill.main.{RootModule, RunScript, SelectMode}
import mill.main.TokenReaders._
import mill.define.{Discover, Segments}

import java.net.URLClassLoader

/**
 * Logic around bootstrapping Mill, creating a [[MillBuildRootModule.BootstrapModule]]
 * and compiling builds/meta-builds and classloading their [[RootModule]]s so we
 * can evaluate the requested tasks on the [[RootModule]] representing the user's
 * `build.sc` file.
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
    config: MillCliConfig,
    env: Map[String, String],
    threadCount: Option[Int],
    targetsAndParams: Seq[String],
    prevRunnerState: RunnerState,
    logger: ColorLogger
) {

  val millBootClasspath = MillBuildBootstrap.prepareMillBootClasspath(projectRoot / "out")

  def evaluate(): Watching.Result[RunnerState] = {
    val runnerState = evaluateRec(0)

    for ((frame, depth) <- runnerState.frames.zipWithIndex) {
      os.write.over(
        recOut(depth) / "mill-runner-state.json",
        upickle.default.write(frame.loggedData, indent = 4),
        createFolders = true
      )
    }

    pprint.log(runnerState.frames.map(_.evalWatched.map(_.pretty)))
    Watching.Result(
      watched = runnerState.frames.flatMap(_.evalWatched),
      error = runnerState.errorOpt,
      result = runnerState
    )
  }

  def getRootModule0(runClassLoader: URLClassLoader): RootModule = {
    val cls = runClassLoader.loadClass("millbuild.build$")
    cls.getField("MODULE$").get(cls).asInstanceOf[RootModule]
  }

  def evaluateRec(depth: Int): RunnerState = {
    // println(s"+evaluateRec($depth) " + recRoot(depth))
    val prevFrameOpt = prevRunnerState.frames.lift(depth)
    val prevOuterFrameOpt = prevRunnerState.frames.lift(depth - 1)

    val nestedRunnerState =
      if (!os.exists(recRoot(depth) / "build.sc")) {
        if (depth == 0) {
          RunnerState(None, Nil, Some("build.sc file not found. Are you in a Mill project folder?"))
        } else {

          val bootstrapModule =
            new MillBuildRootModule.BootstrapModule(projectRoot, recRoot(depth), millBootClasspath)(
              mill.main.RootModule.Info(
                recRoot(depth),
                Discover[MillBuildRootModule.BootstrapModule]
              )
            )
          RunnerState(Some(bootstrapModule), Nil, None)
        }
      } else {
        evaluateRec(depth + 1)
      }

    val res = if (nestedRunnerState.errorOpt.isDefined) {
      nestedRunnerState.add(errorOpt = nestedRunnerState.errorOpt)
    } else {
      val rootModule0 = nestedRunnerState.frames.headOption match {
        case None => nestedRunnerState.bootstrapModuleOpt.get
        case Some(nestedFrame) => getRootModule0(nestedFrame.classLoaderOpt.get)
      }

      val childRootModules: Seq[RootModule] = rootModule0
        .millInternal
        .reflectNestedObjects[RootModule]()

      val rootModuleOrErr = childRootModules match {
        case Seq() => Right(rootModule0)
        case Seq(child) =>
          val invalidChildModules = rootModule0.millModuleDirectChildren.filter(_ ne child)
          if (invalidChildModules.isEmpty) Right(child)
          else Left(
            // We can't get use `child.toString` here, because as a RootModule
            // it's segments are empty and it's toString is ""
            s"RootModule ${child.getClass.getSimpleName} cannot have other " +
              s"modules defined outside of it: ${invalidChildModules.mkString(",")}"
          )

        case multiple =>
          Left(
            s"Only one RootModule can be defined in a build, not " +
              s"${multiple.size}: ${multiple.map(_.getClass.getName).mkString(",")}"
          )
      }

      val validatedRootModuleOrErr = rootModuleOrErr.filterOrElse(
        rootModule =>
          depth == 0 || rootModule.isInstanceOf[MillBuildRootModule],
        s"Root module in ${recRoot(depth).relativeTo(projectRoot)}/build.sc must be of ${classOf[mill.runner.MillBuildRootModule]}"
      )

      validatedRootModuleOrErr match {
        case Left(err) => nestedRunnerState.add(errorOpt = Some(err))
        case Right(rootModule) =>
          val evaluator = makeEvaluator(
            prevFrameOpt.map(_.workerCache).getOrElse(Map.empty),
            nestedRunnerState.frames.lastOption.map(_.scriptImportGraph).getOrElse(Map.empty),
            rootModule,
            // We want to use the grandparent buildHash, rather than the parent
            // buildHash, because the parent build changes are instead detected
            // by analyzing the scriptImportGraph in a more fine-grained manner.
            nestedRunnerState
              .frames
              .dropRight(1)
              .headOption
              .map(_.runClasspath)
              .getOrElse(millBootClasspath.map(PathRef(_)))
              .map(p => (p.path, p.sig))
              .hashCode(),
            depth
          )

          if (depth != 0) processRunClasspath(
            nestedRunnerState,
            rootModule,
            evaluator,
            prevFrameOpt,
            prevOuterFrameOpt
          )
          else processFinalTargets(nestedRunnerState, rootModule, evaluator)
      }
    }
    // println(s"-evaluateRec($depth) " + recRoot(depth))
    res
  }

  /**
   * Handles the compilation of `build.sc` or one of the meta-builds. These
   * cases all only need us to run evaluate `runClasspath` and
   * `scriptImportGraph` to instantiate their classloader/`RootModule` to feed
   * into the next level's [[Evaluator]].
   *
   * Note that if the `runClasspath` doesn't change, we re-use the previous
   * classloader, saving us from having to re-instantiate it and for the code
   * inside to be re-JITed
   */
  def processRunClasspath(
      nestedRunnerState: RunnerState,
      rootModule: RootModule,
      evaluator: Evaluator,
      prevFrameOpt: Option[RunnerState.Frame],
      prevOuterFrameOpt: Option[RunnerState.Frame]
  ): RunnerState = {

    MillBuildBootstrap.evaluateWithWatches(
      rootModule,
      evaluator,
      Seq("{runClasspath,scriptImportGraph}")
    ) match {
      case (Left(error), evalWatches, moduleWatches) =>
        val evalState = RunnerState.Frame(
          evaluator.workerCache.toMap,
          evalWatches,
          moduleWatches,
          Map.empty,
          None,
          Nil
        )

        nestedRunnerState.add(frame = evalState, errorOpt = Some(error))

      case (
            Right(Seq(
              runClasspath: Seq[PathRef],
              scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])]
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
          scriptImportGraph,
          Some(classLoader),
          runClasspath
        )

        nestedRunnerState.add(frame = evalState)
    }
  }

  /**
   * Handles the final evaluation of the user-provided targets. Since there are
   * no further levels to evaluate, we do not need to save a `scriptImportGraph`,
   * classloader, or runClasspath.
   */
  def processFinalTargets(
      nestedRunnerState: RunnerState,
      rootModule: RootModule,
      evaluator: Evaluator
  ): RunnerState = {

    val (evaled, evalWatched, moduleWatches) =
      MillBuildBootstrap.evaluateWithWatches(rootModule, evaluator, targetsAndParams)


    val evalState = RunnerState.Frame(
      evaluator.workerCache.toMap,
      evalWatched,
      moduleWatches,
      Map.empty,
      None,
      Nil
    )

    nestedRunnerState.add(frame = evalState, errorOpt = evaled.left.toOption)
  }

  def makeEvaluator(
      workerCache: Map[Segments, (Int, Any)],
      scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])],
      rootModule: RootModule,
      millClassloaderSigHash: Int,
      depth: Int
  ): Evaluator = {

    val bootLogPrefix =
      if (depth == 0) ""
      else "[" + (Seq.fill(depth - 1)("mill-build") ++ Seq("build.sc")).mkString("/") + "] "

    Evaluator(
      config.home,
      recOut(depth),
      recOut(depth),
      rootModule,
      PrefixLogger(logger, "", tickerContext = bootLogPrefix),
      millClassloaderSigHash
    )
      .withWorkerCache(workerCache.to(collection.mutable.Map))
      .withEnv(env)
      .withFailFast(!config.keepGoing.value)
      .withThreadCount(threadCount)
      .withScriptImportGraph(scriptImportGraph)
  }

  def recRoot(depth: Int) = projectRoot / Seq.fill(depth)("mill-build")
  def recOut(depth: Int) = projectRoot / "out" / Seq.fill(depth)("mill-build")

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
    val millLauncherOpt =
      if (
        os.isFile(selfClassLocation) &&
        !Set("zip", "jar", "class").contains(selfClassLocation.ext)
      ) {

        val millLauncher =
          millBuildBase / "mill-launcher" / s"${BuildInfo.millVersion}.jar"

        if (!os.exists(millLauncher)) {
          os.copy(selfClassLocation, millLauncher, createFolders = true, replaceExisting = true)
        }
        Some(millLauncher)
      } else None
    enclosingClasspath ++ millLauncherOpt
  }

  def evaluateWithWatches(
      rootModule: RootModule,
      evaluator: Evaluator,
      targetsAndParams: Seq[String]
  ): (Either[String, Seq[Any]], Seq[Watchable], Seq[Watchable]) = {

    val evalTaskResult = RunScript.evaluateTasks(evaluator, targetsAndParams, SelectMode.Separated)
    val moduleWatched = rootModule.watchedValues.toVector

    evalTaskResult match {
      case Left(msg) => (Left(msg), Nil, moduleWatched)
      case Right((watched, evaluated)) =>
        evaluated match {
          case Left(msg) => (Left(msg), watched, moduleWatched)
          case Right(results) => (Right(results.map(_._1)), watched, moduleWatched)
        }
    }
  }
}
