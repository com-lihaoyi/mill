package mill.runner

import mill.internal.PrefixLogger
import mill.define.internal.Watchable
import mill.main.{BuildInfo, RootModule}
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
              new RootModule.Info(
                millBootClasspath,
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
          case Some(nestedFrame) =>
            try Result.Success(getRootModule(nestedFrame.classLoaderOpt.get))
            catch {
              case e: Throwable => Result.Failure(renderFailure(e))
            }
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
      rootModule: RootModule,
      evaluator: Evaluator,
      prevFrameOpt: Option[RunnerState.Frame],
      prevOuterFrameOpt: Option[RunnerState.Frame]
  ): RunnerState = {
    evaluateWithWatches(
      rootModule,
      evaluator,
      Seq("{runClasspath,compile,codeSignatures}"),
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
            Result.Success(Seq(
              runClasspath: Seq[PathRef],
              compile: mill.scalalib.api.CompilationResult,
              codeSignatures: Map[String, Int]
            )),
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
          codeSignatures,
          Some(classLoader),
          runClasspath,
          Some(compile.classes),
          Option(evaluator)
        )

        nestedState.add(frame = evalState)
      case _ => ???
    }
  }

  /**
   * Handles the final evaluation of the user-provided targets. Since there are
   * no further levels to evaluate, we do not need to save a `scriptImportGraph`,
   * classloader, or runClasspath.
   */
  def processFinalTargets(
      nestedState: RunnerState,
      rootModule: RootModule,
      evaluator: Evaluator
  ): RunnerState = {

    assert(nestedState.frames.forall(_.evaluator.isDefined))

    val (evaled, evalWatched, moduleWatches) = Evaluator.allBootstrapEvaluators.withValue(
      Evaluator.AllBootstrapEvaluators(Seq(evaluator) ++ nestedState.frames.flatMap(_.evaluator))
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
      workerCache: Map[Segments, (Int, Val)],
      codeSignatures: Map[String, Int],
      rootModule: BaseModule,
      millClassloaderSigHash: Int,
      millClassloaderIdentityHash: Int,
      depth: Int,
      actualBuildFileName: Option[String] = None
  ): Evaluator = {

    val bootLogPrefix: Seq[String] =
      if (depth == 0) Nil
      else Seq(
        (Seq.fill(depth - 1)(millBuild) ++
          Seq(actualBuildFileName.getOrElse("<build>")))
          .mkString("/")
      )

    val outPath = recOut(output, depth)
    val baseLogger = new PrefixLogger(logger, bootLogPrefix)
    lazy val evaluator: Evaluator = new mill.eval.EvaluatorImpl(
      allowPositionalCommandArgs = allowPositionalCommandArgs,
      selectiveExecution = selectiveExecution,
      execution = new mill.exec.Execution(
        baseLogger = baseLogger,
        chromeProfileLogger = new JsonArrayLogger.ChromeProfile(outPath / millChromeProfile),
        profileLogger = new JsonArrayLogger.Profile(outPath / millProfile),
        workspace = projectRoot,
        outPath = outPath,
        externalOutPath = outPath,
        rootModule = rootModule,
        classLoaderSigHash = millClassloaderSigHash,
        classLoaderIdentityHash = millClassloaderIdentityHash,
        workerCache = workerCache.to(collection.mutable.Map),
        env = env,
        failFast = !keepGoing,
        threadCount = threadCount,
        codeSignatures = codeSignatures,
        systemExit = systemExit,
        exclusiveSystemStreams = streams0,
        getEvaluator = () => evaluator
      )
    )

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
      rootModule: RootModule,
      evaluator: Evaluator,
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
      case Result.Success(Evaluator.Result(watched, evaluated, _, _)) =>
        evaluated match {
          case Result.Failure(msg) =>
            (Result.Failure(msg), watched ++ addedEvalWatched, moduleWatched)
          case Result.Success(results) =>
            (Result.Success(results), watched ++ addedEvalWatched, moduleWatched)
        }
    }
  }

  def getRootModule(runClassLoader: URLClassLoader): RootModule = {
    val buildClass = runClassLoader.loadClass(s"$globalPackagePrefix.${wrapperObjectName}$$")
    os.checker.withValue(EvaluatorImpl.resolveChecker) {
      buildClass.getField("MODULE$").get(buildClass).asInstanceOf[RootModule]
    }
  }

  def recRoot(projectRoot: os.Path, depth: Int): os.Path = {
    projectRoot / Seq.fill(depth)(millBuild)
  }

  def recOut(output: os.Path, depth: Int): os.Path = {
    output / Seq.fill(depth)(millBuild)
  }

}
