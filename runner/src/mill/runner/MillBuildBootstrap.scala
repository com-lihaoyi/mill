package mill.runner

import mill.util.{ColorLogger, PrefixLogger, Watchable}
import mill.main.BuildInfo
import mill.api.{PathRef, Val, internal}
import mill.eval.Evaluator
import mill.main.RunScript
import mill.resolve.SelectMode
import mill.define.{BaseModule, Discover, Segments}
import mill.main.client.OutFiles._

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
    home: os.Path,
    keepGoing: Boolean,
    imports: Seq[String],
    env: Map[String, String],
    threadCount: Option[Int],
    targetsAndParams: Seq[String],
    prevRunnerState: RunnerState,
    logger: ColorLogger,
    disableCallgraphInvalidation: Boolean,
    needBuildSc: Boolean,
    requestedMetaLevel: Option[Int],
    allowPositionalCommandArgs: Boolean
) {
  import MillBuildBootstrap._

  val millBootClasspath: Seq[os.Path] = prepareMillBootClasspath(projectRoot / out)
  val millBootClasspathPathRefs: Seq[PathRef] = millBootClasspath.map(PathRef(_, quick = true))

  def evaluate(): Watching.Result[RunnerState] = CliImports.withValue(imports) {
    val runnerState = evaluateRec(0)

    for ((frame, depth) <- runnerState.frames.zipWithIndex) {
      os.write.over(
        recOut(projectRoot, depth) / millRunnerState,
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

  def evaluateRec(depth: Int): RunnerState = {
    // println(s"+evaluateRec($depth) " + recRoot(projectRoot, depth))
    val prevFrameOpt = prevRunnerState.frames.lift(depth)
    val prevOuterFrameOpt = prevRunnerState.frames.lift(depth - 1)

    val requestedDepth = requestedMetaLevel.filter(_ >= 0).getOrElse(0)

    val nestedState: RunnerState =
      if (depth == 0) {
        // On this level we typically want assume a Mill project, which means we want to require an existing `build.sc`.
        // Unfortunately, some targets also make sense without a `build.sc`, e.g. the `init` command.
        // Hence we only report a missing `build.sc` as an problem if the command itself does not succeed.
        lazy val state = evaluateRec(depth + 1)
        if (os.exists(recRoot(projectRoot, depth) / "build.sc")) state
        else {
          val msg = s"build.sc file not found in $projectRoot. Are you in a Mill project folder?"
          if (needBuildSc) {
            RunnerState(None, Nil, Some(msg))
          } else {
            state match {
              case RunnerState(bootstrapModuleOpt, frames, Some(error)) =>
                // Add a potential clue (missing build.sc) to the underlying error message
                RunnerState(bootstrapModuleOpt, frames, Some(msg + "\n" + error))
              case state => state
            }
          }
        }
      } else {
        val parsedScriptFiles = FileImportGraph.parseBuildFiles(
          projectRoot,
          recRoot(projectRoot, depth) / os.up
        )

        if (parsedScriptFiles.millImport) evaluateRec(depth + 1)
        else {
          val bootstrapModule =
            new MillBuildRootModule.BootstrapModule(
              projectRoot,
              recRoot(projectRoot, depth),
              millBootClasspath
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
        val validatedRootModulesOrErr = nestedState.frames.headOption match {
          case None =>
            getChildRootModule(nestedState.bootstrapModuleOpt.get, depth, projectRoot).map(Seq(_))

          case Some(nestedFrame) =>
            getRootModule(
              nestedFrame.compileOutput.get,
              nestedFrame.classLoaderOpt.get,
              depth,
              projectRoot
            )
        }

        validatedRootModulesOrErr match {
          case Left(err) => nestedState.add(errorOpt = Some(err))
          case Right(rootModules) =>
            val evaluator = makeEvaluator(
              prevFrameOpt.map(_.workerCache).getOrElse(Map.empty),
              nestedState.frames.headOption.map(_.scriptImportGraph).getOrElse(Map.empty),
              nestedState.frames.headOption.map(_.methodCodeHashSignatures).getOrElse(Map.empty),
              rootModules,
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
                rootModules,
                evaluator,
                prevFrameOpt,
                prevOuterFrameOpt
              )

              if (retState.errorOpt.isEmpty && depth == requestedDepth) {
                // TODO: print some message and indicate actual evaluated frame
                val evalRet = processFinalTargets(nestedState, rootModules, evaluator)
                if (evalRet.errorOpt.isEmpty) retState
                else evalRet
              } else
                retState

            } else {
              processFinalTargets(nestedState, rootModules, evaluator)
            }
        }
      }
    // println(s"-evaluateRec($depth) " + recRoot(projectRoot, depth))
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
      nestedState: RunnerState,
      rootModules: Seq[BaseModule],
      evaluator: Evaluator,
      prevFrameOpt: Option[RunnerState.Frame],
      prevOuterFrameOpt: Option[RunnerState.Frame]
  ): RunnerState = {
    evaluateWithWatches(
      rootModules,
      evaluator,
      Seq("{runClasspath,compile,scriptImportGraph,methodCodeHashSignatures}")
    ) match {
      case (Left(error), evalWatches, moduleWatches) =>
        val evalState = RunnerState.Frame(
          evaluator.workerCache.toMap,
          evalWatches,
          moduleWatches,
          Map.empty,
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
              Val(scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])]),
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
          scriptImportGraph,
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
      rootModules: Seq[BaseModule],
      evaluator: Evaluator
  ): RunnerState = {

    assert(nestedState.frames.forall(_.evaluator.isDefined))

    val (evaled, evalWatched, moduleWatches) = Evaluator.allBootstrapEvaluators.withValue(
      Evaluator.AllBootstrapEvaluators(Seq(evaluator) ++ nestedState.frames.flatMap(_.evaluator))
    ) {
      evaluateWithWatches(rootModules, evaluator, targetsAndParams)
    }

    val evalState = RunnerState.Frame(
      evaluator.workerCache.toMap,
      evalWatched,
      moduleWatches,
      Map.empty,
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
      scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])],
      methodCodeHashSignatures: Map[String, Int],
      rootModules: Seq[BaseModule],
      millClassloaderSigHash: Int,
      millClassloaderIdentityHash: Int,
      depth: Int
  ): Evaluator = {

    val bootLogPrefix =
      if (depth == 0) ""
      else "[" + (Seq.fill(depth - 1)(millBuild) ++ Seq("build.sc")).mkString("/") + "] "

    mill.eval.EvaluatorImpl(
      home,
      projectRoot,
      recOut(projectRoot, depth),
      recOut(projectRoot, depth),
      rootModules,
      PrefixLogger(logger, "", tickerContext = bootLogPrefix),
      classLoaderSigHash = millClassloaderSigHash,
      classLoaderIdentityHash = millClassloaderIdentityHash,
      workerCache = workerCache.to(collection.mutable.Map),
      env = env,
      failFast = !keepGoing,
      threadCount = threadCount,
      scriptImportGraph = scriptImportGraph,
      methodCodeHashSignatures = methodCodeHashSignatures,
      disableCallgraphInvalidation = disableCallgraphInvalidation,
      allowPositionalCommandArgs = allowPositionalCommandArgs
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
      rootModules: Seq[BaseModule],
      evaluator: Evaluator,
      targetsAndParams: Seq[String]
  ): (Either[String, Seq[Any]], Seq[Watchable], Seq[Watchable]) = {
    rootModules.foreach(_.evalWatchedValues.clear())
    val evalTaskResult =
      RunScript.evaluateTasksNamed(evaluator, targetsAndParams, SelectMode.Separated)
    val moduleWatched = rootModules.flatMap(_.watchedValues).toVector
    val addedEvalWatched = rootModules.flatMap(_.evalWatchedValues).toVector

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

  def getRootModule(
      compileOut: PathRef,
      runClassLoader: URLClassLoader,
      depth: Int,
      projectRoot: os.Path
  ): Either[String, Seq[BaseModule]] = {

    val packageClassNames = os
      .walk(compileOut.path)
      .map(_.relativeTo(compileOut.path).segments)
      .collect { case "build" +: rest :+ "package$.class" =>
        ("build" +: rest :+ "package$").mkString(".")
      }

    val packageClasses = packageClassNames.map(runClassLoader.loadClass(_))
    val rootModule0s =
      packageClasses.map(cls => cls.getField("MODULE$").get(cls).asInstanceOf[BaseModule])
    val children = rootModule0s.map(getChildRootModule(_, depth, projectRoot))
    children.flatMap(_.left.toOption) match {
      case Nil => Right[String, Seq[BaseModule]](children.map(_.toOption.get))
      case errors => Left(errors.mkString("\n"))
    }
  }

  def getChildRootModule(
      rootModule0: BaseModule,
      depth: Int,
      projectRoot: os.Path
  ): Either[String, BaseModule] = {

    val childRootModules: Seq[BaseModule] = rootModule0
      .millInternal
      .reflectNestedObjects[BaseModule]()

    val rootModuleOrErr = childRootModules match {
      case Seq() => Right(rootModule0)
      case Seq(child) =>
        val invalidChildModules = rootModule0.millModuleDirectChildren.filter(_ ne child)
        if (invalidChildModules.isEmpty) Right(child)
        else Left(
          // We can't get use `child.toString` here, because as a RootModule
          // it's segments are empty and it's toString is ""
          s"RootModule ${child.getClass.getSimpleName.stripSuffix("$")} cannot have other " +
            s"modules defined outside of it: ${invalidChildModules.mkString(",")}"
        )

      case multiple =>
        Left(
          s"Only one RootModule can be defined in a build, not " +
            s"${multiple.size}: ${multiple.map(_.getClass.getName).mkString(",")}"
        )
    }

    rootModuleOrErr.filterOrElse(
      rootModule =>
        depth == 0 || rootModule.isInstanceOf[mill.runner.MillBuildRootModule],
      s"Root module in ${recRoot(projectRoot, depth).relativeTo(projectRoot)}/build.sc must be of ${classOf[
          MillBuildRootModule
        ]}, not ${rootModuleOrErr.map(_.getClass)}"
    )
  }

  def recRoot(projectRoot: os.Path, depth: Int): os.Path = {
    projectRoot / Seq.fill(depth)(millBuild)
  }

  def recOut(projectRoot: os.Path, depth: Int): os.Path = {
    projectRoot / out / Seq.fill(depth)(millBuild)
  }

}
