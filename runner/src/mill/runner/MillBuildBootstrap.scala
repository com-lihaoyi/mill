package mill.runner

import mill.internal.PrefixLogger
import mill.define.internal.Watchable
import mill.define.RootModule0
import mill.util.BuildInfo
import mill.runner.api.{RootModuleApi, EvaluatorApi}
import mill.constants.CodeGenConstants.*
import mill.api.{Logger, PathRef, Result, SystemStreams, Val, WorkspaceRoot, internal}
import mill.define.{BaseModule, Evaluator, Segments, SelectMode}
import mill.exec.JsonArrayLogger
import mill.constants.OutFiles.{millBuild, millChromeProfile, millProfile, millRunnerState}
import mill.eval.EvaluatorImpl
import mill.runner.worker.api.MillScalaParser
import mill.runner.worker.ScalaCompilerWorker

import java.io.File
import java.net.URLClassLoader
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.Using

/**
 * Logic around bootstrapping Mill, creating a [[MillBuildRootModule.BootstrapModule]]
 * and compiling builds/meta-builds and classloading their [[RootModule0]]s so we
 * can evaluate the requested tasks on the [[RootModule0]] representing the user's
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
    keepGoing: Boolean,
    imports: Seq[String],
    env: Map[String, String],
    threadCount: Option[Int],
    targetsAndParams: Seq[String],
    prevRunnerState: RunnerState,
    logger: Logger,
    needBuildFile: Boolean,
    requestedMetaLevel: Option[Int],
    allowPositionalCommandArgs: Boolean,
    systemExit: Int => Nothing,
    streams0: SystemStreams,
    selectiveExecution: Boolean,
    scalaCompilerWorker: ScalaCompilerWorker.ResolvedWorker
) { outer =>
  import MillBuildBootstrap._

  val millBootClasspath: Seq[os.Path] = prepareMillBootClasspath(output)
  val millBootClasspathPathRefs: Seq[PathRef] = millBootClasspath.map(PathRef(_, quick = true))

  def parserBridge: MillScalaParser = {
    scalaCompilerWorker.worker
  }

  def evaluate(): Watching.Result[RunnerState] = CliImports.withValue(imports) {
    val runnerState = evaluateRec(0)

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

  def evaluateRec(depth: Int): RunnerState = {
    // println(s"+evaluateRec($depth) " + recRoot(projectRoot, depth))
    val prevFrameOpt = prevRunnerState.frames.lift(depth)
    val prevOuterFrameOpt = prevRunnerState.frames.lift(depth - 1)

    val requestedDepth = requestedMetaLevel.filter(_ >= 0).getOrElse(0)

    val nestedState: RunnerState =
      if (depth == 0) {
        // On this level we typically want to assume a Mill project, which means we want to require an existing `build.mill`.
        // Unfortunately, some targets also make sense without a `build.mill`, e.g. the `init` command.
        // Hence, we only report a missing `build.mill` as a problem if the command itself does not succeed.
        lazy val state = evaluateRec(depth + 1)
        if (
          rootBuildFileNames.asScala.exists(rootBuildFileName =>
            os.exists(recRoot(projectRoot, depth) / rootBuildFileName)
          )
        ) state
        else {
          val msg =
            s"No build file (${rootBuildFileNames.asScala.mkString(", ")}) found in $projectRoot. Are you in a Mill project directory?"
          if (needBuildFile) {
            RunnerState(None, Nil, Some(msg), None)
          } else {
            state match {
              case RunnerState(bootstrapModuleOpt, frames, Some(error), None) =>
                // Add a potential clue (missing build.mill) to the underlying error message
                RunnerState(bootstrapModuleOpt, frames, Some(msg + "\n" + error))
              case state => state
            }
          }
        }
      } else {
        val parsedScriptFiles = FileImportGraph.parseBuildFiles(
          parserBridge,
          projectRoot,
          recRoot(projectRoot, depth) / os.up,
          output
        )

        if (parsedScriptFiles.metaBuild) evaluateRec(depth + 1)
        else {
          val bootstrapModule =
            new MillBuildRootModule.BootstrapModule()(
              new RootModule0.Info(
                scalaCompilerWorker.classpath,
                recRoot(projectRoot, depth),
                output,
                projectRoot
              ),
              scalaCompilerWorker.constResolver
            )
          RunnerState(Some(bootstrapModule), Nil, None, Some(parsedScriptFiles.buildFile))
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
          // mainly because we didn't even construct (compile) its classpath
          None,
          None
        )
        nestedState.add(frame = evalState, errorOpt = None)
      } else {

        def renderFailure(e: Throwable): String = {
          e match {
            case e: ExceptionInInitializerError if e.getCause != null => renderFailure(e.getCause)
            case e: NoClassDefFoundError if e.getCause != null => renderFailure(e.getCause)
            case _ =>
              val msg =
                e.toString +
                  "\n" +
                  e.getStackTrace.dropRight(new Exception().getStackTrace.length).mkString("\n")

              msg
          }
        }

        val rootModuleRes = nestedState.frames.headOption match {
          case None => Result.Success(nestedState.bootstrapModuleOpt.get)
          case Some(nestedFrame) => getRootModule(nestedFrame.classLoaderOpt.get)
        }

        rootModuleRes match {
          case Result.Failure(err) => nestedState.add(errorOpt = Some(err))
          case Result.Success(rootModule) =>

            Using.resource(makeEvaluator(
              prevFrameOpt.map(_.workerCache).getOrElse(Map.empty),
              nestedState.frames.headOption.map(_.codeSignatures).getOrElse(Map.empty),
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
              depth,
              actualBuildFileName = nestedState.buildFile
            )) { evaluator =>
              if (depth == requestedDepth) processFinalTargets(nestedState, rootModule, evaluator)
              else if (depth <= requestedDepth) nestedState
              else {
                processRunClasspath(
                  nestedState,
                  rootModule,
                  evaluator,
                  prevFrameOpt,
                  prevOuterFrameOpt
                )
              }
            }
        }
      }

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
      rootModule: RootModuleApi,
      evaluator: EvaluatorApi,
      prevFrameOpt: Option[RunnerState.Frame],
      prevOuterFrameOpt: Option[RunnerState.Frame]
  ): RunnerState = {
    evaluateWithWatches(
      rootModule,
      evaluator,
      Seq("millBuildRootModuleResult"),
      selectiveExecution = false
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
              runClasspath: Seq[String],
              compileClasses: String,
              codeSignatures: Map[String, Int]
            ))),
            evalWatches,
            moduleWatches
          ) =>
        val runClasspathChanged = !prevFrameOpt.exists(
          _.runClasspath.map(_.sig).sum == runClasspath.map(f => PathRef(os.Path(f)).sig).sum
        )

        // handling module watching is a bit weird; we need to know whether
        // to create a new classloader immediately after the `runClasspath`
        // is compiled, but we only know what the respective `moduleWatched`
        // contains after the evaluation on this classloader has executed, which
        // happens one level up in the recursion. Thus, to check whether
        // `moduleWatched` needs us to re-create the classloader, we have to
        // look at the `moduleWatched` of one frame up (`prevOuterFrameOpt`),
        // and not the `moduleWatched` from the current frame (`prevFrameOpt`)
        val moduleWatchChanged =
          prevOuterFrameOpt.exists(_.moduleWatched.exists(w => !Watching.validate(w)))

        val classLoader = if (runClasspathChanged || moduleWatchChanged) {
          // Make sure we close the old classloader every time we create a new
          // one, to avoid memory leaks
          prevFrameOpt.foreach(_.classLoaderOpt.foreach(_.close()))
          val cl = new RunnerState.URLClassLoader(
            runClasspath.map(os.Path(_).toNIO.toUri.toURL).toArray,
            null
          ) {
            val sharedCl = classOf[MillBuildBootstrap].getClassLoader
            val sharedPrefixes = Seq("java.", "javax.", "scala.", "mill.runner.api")
            override def findClass(name: String): Class[?] =
              if (sharedPrefixes.exists(name.startsWith)) sharedCl.loadClass(name)
              else super.findClass(name)
          }
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
          runClasspath.map(f => PathRef(os.Path(f))),
          Some(PathRef(os.Path(compileClasses))),
          Option(evaluator)
        )

        nestedState.add(frame = evalState)

      case unknown => sys.error(unknown.toString())
    }
  }

  /**
   * Handles the final evaluation of the user-provided targets. Since there are
   * no further levels to evaluate, we do not need to save a `scriptImportGraph`,
   * classloader, or runClasspath.
   */
  def processFinalTargets(
      nestedState: RunnerState,
      rootModule: RootModuleApi,
      evaluator: EvaluatorApi
  ): RunnerState = {

    assert(nestedState.frames.forall(_.evaluator.isDefined))

    val (evaled, evalWatched, moduleWatches) =
      mill.runner.api.EvaluatorApi.allBootstrapEvaluators.withValue(
        mill.runner.api.EvaluatorApi.AllBootstrapEvaluators(Seq(
          evaluator
        ) ++ nestedState.frames.flatMap(_.evaluator))
      ) {
        evaluateWithWatches(rootModule, evaluator, targetsAndParams, selectiveExecution)
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

    nestedState.add(frame = evalState, errorOpt = evaled.toEither.left.toOption)
  }

  def makeEvaluator(
      workerCache: Map[String, (Int, Val)],
      codeSignatures: Map[String, Int],
      rootModule: RootModuleApi,
      millClassloaderSigHash: Int,
      millClassloaderIdentityHash: Int,
      depth: Int,
      actualBuildFileName: Option[String] = None
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
        threadCount,
        codeSignatures,
        systemExit,
        streams0,
        () => evaluator
      )
    ).asInstanceOf[EvaluatorApi]

    evaluator
  }

}

@internal
object MillBuildBootstrap {

  def classpath(classLoader: ClassLoader): Vector[os.Path] = {

    var current = classLoader
    val files = collection.mutable.Buffer.empty[os.Path]
    val seenClassLoaders = collection.mutable.Buffer.empty[ClassLoader]
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
          val f = os.Path(p, WorkspaceRoot.workspaceRoot)
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
      rootModule: RootModuleApi,
      evaluator: EvaluatorApi,
      targetsAndParams: Seq[String],
      selectiveExecution: Boolean
  ): (Result[Seq[Any]], Seq[Watchable], Seq[Watchable]) = {
    rootModule.evalWatchedValues.clear()
    val evalTaskResult =
      mill.api.ClassLoader.withContextClassLoader(rootModule.getClass.getClassLoader) {
        evaluator.evaluate(
          targetsAndParams,
          SelectMode.Separated,
          selectiveExecution = selectiveExecution
        )
      }

    val moduleWatched = rootModule.watchedValues.toVector
    val addedEvalWatched = rootModule.evalWatchedValues.toVector

    evalTaskResult match {
      case Result.Failure(msg) => (Result.Failure(msg), Nil, moduleWatched)
      case Result.Success(res: EvaluatorApi.Result[Any]) =>
        res.values match {
          case Result.Failure(msg) =>
            (Result.Failure(msg), res.watchable ++ addedEvalWatched, moduleWatched)
          case Result.Success(results) =>
            (Result.Success(results), res.watchable ++ addedEvalWatched, moduleWatched)
        }
    }
  }

  def getRootModule(runClassLoader: URLClassLoader): Result[RootModuleApi] = {
    val buildClass = runClassLoader.loadClass(s"$globalPackagePrefix.wrapper_object_getter")

    val valueMethod = buildClass.getMethod("value")
    mill.api.ExecResult.catchWrapException { valueMethod.invoke(null).asInstanceOf[RootModuleApi] }
  }

  def recRoot(projectRoot: os.Path, depth: Int): os.Path = {
    projectRoot / Seq.fill(depth)(millBuild)
  }

  def recOut(output: os.Path, depth: Int): os.Path = {
    output / Seq.fill(depth)(millBuild)
  }

}
