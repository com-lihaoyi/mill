package mill.runner

import scala.jdk.CollectionConverters.ListHasAsScala

import coursier.Repository
import mill.*
import mill.api.{PathRef, Result, internal}
import mill.define.{Discover, Task}
import mill.scalalib.{BoundDep, Dep, DepSyntax, Lib, ScalaModule}
import mill.util.Jvm
import mill.scalalib.api.JvmWorkerUtil
import mill.scalalib.api.{CompilationResult, Versions}
import mill.constants.OutFiles.*
import mill.constants.CodeGenConstants.buildFileExtensions
import mill.main.{BuildInfo, RootModule}
import mill.runner.worker.ScalaCompilerWorker
import mill.runner.worker.api.ScalaCompilerWorkerApi
import scala.util.Try

import mill.define.Target
import mill.runner.worker.api.MillScalaParser

/**
 * Mill module for pre-processing a Mill `build.mill` and related files and then
 * compiling them as a normal [[ScalaModule]]. Parses `build.mill`, walks any
 * `import $file`s, wraps the script files to turn them into valid Scala code
 * and then compiles them with the `ivyDeps` extracted from the `import $ivy`
 * calls within the scripts.
 */
@internal
abstract class MillBuildRootModule()(implicit
    rootModuleInfo: RootModule.Info,
    scalaCompilerResolver: ScalaCompilerWorker.Resolver
) extends RootModule() with ScalaModule {
  override def bspDisplayName0: String = rootModuleInfo
    .projectRoot
    .relativeTo(rootModuleInfo.topLevelProjectRoot)
    .segments
    .++(super.bspDisplayName0.split("/"))
    .mkString("/")

  override def moduleDir: os.Path = rootModuleInfo.projectRoot / os.up / millBuild
  override def intellijModulePath: os.Path = moduleDir / os.up

  override def scalaVersion: T[String] = BuildInfo.scalaVersion

  val scriptSourcesPaths = FileImportGraph
    .walkBuildFiles(rootModuleInfo.projectRoot / os.up, rootModuleInfo.output)
    .sorted

  /**
   * All script files (that will get wrapped later)
   * @see [[generateScriptSources]]
   */
  def scriptSources: Target[Seq[PathRef]] = Task.Sources(
    scriptSourcesPaths.map(Result.Success(_))* // Ensure ordering is deterministic
  )

  def parseBuildFiles: T[FileImportGraph] = Task {
    scriptSources()
    MillBuildRootModule.parseBuildFiles(compilerWorker(), rootModuleInfo)
  }

  private[runner] def compilerWorker: Worker[ScalaCompilerWorkerApi] = Task.Worker {
    scalaCompilerResolver.resolve(rootModuleInfo.compilerWorkerClasspath)
  }

  override def repositoriesTask: Task[Seq[Repository]] = {
    val importedRepos = Task.Anon {
      val repos = parseBuildFiles().repos.map { case (repo, srcFile) =>
        val relFile = Try {
          srcFile.relativeTo(Task.workspace)
        }.recover { case _ => srcFile }.get
        Jvm.repoFromString(
          repo,
          s"buildfile `${relFile}`: import $$repo.`${repo}`"
        )
      }
      repos.find(_.isInstanceOf[Result.Failure]) match {
        case Some(error) => error
        case None =>
          val res = repos.collect { case Result.Success(v) => v }.flatten
          Result.Success(res)
      }
    }

    Task.Anon {
      super.repositoriesTask() ++ importedRepos()
    }
  }

  def cliImports: T[Seq[String]] = Task.Input {
    val imports = CliImports.value
    if (imports.nonEmpty) {
      Task.log.debug(s"Using cli-provided runtime imports: ${imports.mkString(", ")}")
    }
    imports
  }

  override def ivyDeps = Task {
    Seq.from(
      MillIvy.processMillIvyDepSignature(parseBuildFiles().ivyDeps)
        .map(mill.scalalib.Dep.parse)
    ) ++
      Seq(ivy"com.lihaoyi::mill-moduledefs:${Versions.millModuledefsVersion}")
  }

  override def runIvyDeps = Task {
    val imports = cliImports()
    val ivyImports = imports.collect { case s"ivy:$rest" => rest }
    Seq.from(
      MillIvy.processMillIvyDepSignature(ivyImports.toSet)
        .map(mill.scalalib.Dep.parse)
    )
  }

  override def platformSuffix: T[String] = s"_mill${BuildInfo.millBinPlatform}"

  override def generatedSources: T[Seq[PathRef]] = Task {
    generateScriptSources()
  }

  def generateScriptSources: T[Seq[PathRef]] = Task {
    val parsed = parseBuildFiles()
    if (parsed.errors.nonEmpty) Result.Failure(parsed.errors.mkString("\n"))
    else {
      CodeGen.generateWrappedSources(
        rootModuleInfo.projectRoot / os.up,
        parsed.seenScripts,
        Task.dest,
        rootModuleInfo.enclosingClasspath,
        rootModuleInfo.compilerWorkerClasspath,
        rootModuleInfo.topLevelProjectRoot,
        rootModuleInfo.output,
        compilerWorker()
      )
      Result.Success(Seq(PathRef(Task.dest)))
    }
  }

  def codeSignatures: T[Map[String, Int]] = Task(persistent = true) {
    os.remove.all(Task.dest / "previous")
    if (os.exists(Task.dest / "current"))
      os.move.over(Task.dest / "current", Task.dest / "previous")
    val debugEnabled = Task.log.debugEnabled
    val codesig = mill.codesig.CodeSig
      .compute(
        classFiles = os.walk(compile().classes.path).filter(_.ext == "class"),
        upstreamClasspath = compileClasspath().toSeq.map(_.path),
        ignoreCall = { (callSiteOpt, calledSig) =>
          // We can ignore all calls to methods that look like Targets when traversing
          // the call graph. We can do this because we assume `def` Targets are pure,
          // and so any changes in their behavior will be picked up by the runtime build
          // graph evaluator without needing to be accounted for in the post-compile
          // bytecode callgraph analysis.
          def isSimpleTarget(desc: mill.codesig.JvmModel.Desc) =
            (desc.ret.pretty == classOf[mill.define.Target[?]].getName ||
              desc.ret.pretty == classOf[mill.define.Worker[?]].getName) &&
              desc.args.isEmpty

          // We avoid ignoring method calls that are simple trait forwarders, because
          // we need the trait forwarders calls to be counted in order to wire up the
          // method definition that a Target is associated with during evaluation
          // (e.g. `myModuleObject.myTarget`) with its implementation that may be defined
          // somewhere else (e.g. `trait MyModuleTrait{ def myTarget }`). Only that one
          // step is necessary, after that the runtime build graph invalidation logic can
          // take over
          def isForwarderCallsiteOrLambda =
            callSiteOpt.nonEmpty && {
              val callSiteSig = callSiteOpt.get.sig

              (callSiteSig.name == (calledSig.name + "$") &&
                callSiteSig.static &&
                callSiteSig.desc.args.size == 1)
              || (
                // In Scala 3, lambdas are implemented by private instance methods,
                // not static methods, so they fall through the crack of "isSimpleTarget".
                // Here make the assumption that a zero-arg lambda called from a simpleTarget,
                // should in fact be tracked. e.g. see `integration.invalidation[codesig-hello]`,
                // where the body of the `def foo` target is a zero-arg lambda i.e. the argument
                // of `Cacher.cachedTarget`.
                // To be more precise I think ideally we should capture more information in the signature
                isSimpleTarget(callSiteSig.desc) && calledSig.name.contains("$anonfun")
              )
            }

          // We ignore Commands for the same reason as we ignore Targets, and also because
          // their implementations get gathered up all the via the `Discover` macro, but this
          // is primarily for use as external entrypoints and shouldn't really be counted as
          // part of the `millbuild.build#<init>` transitive call graph they would normally
          // be counted as
          def isCommand =
            calledSig.desc.ret.pretty == classOf[mill.define.Command[?]].getName

          // Skip calls to `millDiscover`. `millDiscover` is bundled as part of `RootModule` for
          // convenience, but it should really never be called by any normal Mill module/task code,
          // and is only used by downstream code in `mill.eval`/`mill.resolve`. Thus although CodeSig's
          // conservative analysis considers potential calls from `build_.package_$#<init>` to
          // `millDiscover()`, we can safely ignore that possibility
          def isMillDiscover =
            calledSig.name == "millDiscover$lzyINIT1" ||
              calledSig.name == "millDiscover" ||
              callSiteOpt.exists(_.sig.name == "millDiscover")

          (isSimpleTarget(calledSig.desc) && !isForwarderCallsiteOrLambda) ||
          isCommand ||
          isMillDiscover
        },
        logger = new mill.codesig.Logger(
          Task.dest / "current",
          Option.when(debugEnabled)(Task.dest / "current")
        ),
        prevTransitiveCallGraphHashesOpt = () =>
          Option.when(os.exists(Task.dest / "previous/transitiveCallGraphHashes0.json"))(
            upickle.default.read[Map[String, Int]](
              os.read.stream(Task.dest / "previous/transitiveCallGraphHashes0.json")
            )
          )
      )

    codesig.transitiveCallGraphHashes
  }

  override def sources: T[Seq[PathRef]] = Task {
    scriptSources() ++ {
      if (parseBuildFiles().metaBuild) super.sources()
      else Seq.empty[PathRef]
    }
  }

  override def resources: T[Seq[PathRef]] = Task {
    if (parseBuildFiles().metaBuild) super.resources()
    else Seq.empty[PathRef]
  }

  override def allSourceFiles: T[Seq[PathRef]] = Task {
    val candidates =
      Lib.findSourceFiles(allSources(), Seq("scala", "java") ++ buildFileExtensions.asScala)
    // We need to unlist those files, which we replaced by generating wrapper scripts
    val filesToExclude = Lib.findSourceFiles(scriptSources(), buildFileExtensions.asScala.toSeq)
    candidates.filterNot(filesToExclude.contains).map(PathRef(_))
  }

  def enclosingClasspath: Target[Seq[PathRef]] = Task.Sources(
    rootModuleInfo.enclosingClasspath.map(Result.Success(_))*
  )

  /**
   * Dependencies, which should be transitively excluded.
   * By default, these are the dependencies, which Mill provides itself (via [[unmanagedClasspath]]).
   * We exclude them to avoid incompatible or duplicate artifacts on the classpath.
   */
  protected def resolveDepsExclusions: T[Seq[(String, String)]] = Task {
    val allMillDistModules = BuildInfo.millAllDistDependencies
      .split(',')
      .filter(_.nonEmpty)
      .map { str =>
        str.split(":", 3) match {
          case Array(org, name, _) => (org, name)
          case other =>
            sys.error(
              s"Unexpected misshapen entry in BuildInfo.millAllDistDependencies ('$str', expected 'org:name')"
            )
        }
      }
    val isScala3 = JvmWorkerUtil.isScala3(scalaVersion())
    if (isScala3)
      allMillDistModules.filter(_._2 != "scala-library").toSeq
    else
      allMillDistModules.toSeq
  }

  override def bindDependency: Task[Dep => BoundDep] = Task.Anon { (dep: Dep) =>
    super.bindDependency.apply().apply(dep).exclude(resolveDepsExclusions()*)
  }

  override def unmanagedClasspath: T[Seq[PathRef]] = Task {
    enclosingClasspath()
  }

  override def scalacPluginIvyDeps: T[Seq[Dep]] = Seq(
    ivy"com.lihaoyi:::scalac-mill-moduledefs-plugin:${Versions.millModuledefsVersion}"
  )

  override def scalacOptions: T[Seq[String]] = Task {
    super.scalacOptions() ++
      Seq("-deprecation")
  }

  override def scalacPluginClasspath: T[Seq[PathRef]] =
    super.scalacPluginClasspath() ++ lineNumberPluginClasspath()

  override protected def semanticDbPluginClasspath: T[Seq[PathRef]] =
    super.semanticDbPluginClasspath() ++ lineNumberPluginClasspath()

  def lineNumberPluginClasspath: T[Seq[PathRef]] = Task {
    // millProjectModule("mill-runner-linenumbers", repositoriesTask())
    Seq.empty
  }

  /** Used in BSP IntelliJ, which can only work with directories */
  def dummySources: Sources = Task.Sources(Task.dest)

  def millVersion: Target[String] = Task.Input { BuildInfo.millVersion }

  override def compile: T[CompilationResult] = Task(persistent = true) {
    val mv = millVersion()

    val prevMillVersionFile = Task.dest / s"mill-version"
    val prevMillVersion = Option(prevMillVersionFile)
      .filter(os.exists)
      .map(os.read(_).trim)
      .getOrElse("?")

    if (prevMillVersion != mv) {
      // Mill version changed, drop all previous incremental state
      // see https://github.com/com-lihaoyi/mill/issues/3874
      Task.log.debug(
        s"Detected Mill version change ${prevMillVersion} -> ${mv}. Dropping previous incremental compilation state"
      )
      os.remove.all(Task.dest)
      os.makeDir(Task.dest)
      os.write(prevMillVersionFile, mv)
    }

    // copied from `ScalaModule`
    jvmWorker()
      .worker()
      .compileMixed(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources = Seq.from(allSourceFiles().map(_.path)),
        compileClasspath = compileClasspath().map(_.path),
        javacOptions = javacOptions() ++ mandatoryJavacOptions(),
        scalaVersion = scalaVersion(),
        scalaOrganization = scalaOrganization(),
        scalacOptions = allScalacOptions(),
        compilerClasspath = scalaCompilerClasspath(),
        scalacPluginClasspath = scalacPluginClasspath(),
        reporter = Task.reporter.apply(hashCode),
        reportCachedProblems = zincReportCachedProblems(),
        incrementalCompilation = zincIncrementalCompilation(),
        auxiliaryClassFileExtensions = zincAuxiliaryClassFileExtensions()
      )
  }

}

object MillBuildRootModule {

  class BootstrapModule()(implicit
      rootModuleInfo: RootModule.Info,
      scalaCompilerResolver: ScalaCompilerWorker.Resolver
  ) extends MillBuildRootModule() {
    override lazy val millDiscover = Discover[this.type]
  }

  case class Info(
      enclosingClasspath: Seq[os.Path],
      projectRoot: os.Path,
      output: os.Path,
      topLevelProjectRoot: os.Path
  )

  def parseBuildFiles(
      parser: MillScalaParser,
      millBuildRootModuleInfo: RootModule.Info
  ): FileImportGraph = {
    FileImportGraph.parseBuildFiles(
      parser,
      millBuildRootModuleInfo.topLevelProjectRoot,
      millBuildRootModuleInfo.projectRoot / os.up,
      millBuildRootModuleInfo.output
    )
  }
}
