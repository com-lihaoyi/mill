package mill.runner

import coursier.Repository
import mill._
import mill.api.{PathRef, Result, internal}
import mill.define.{Discover, Task}
import mill.scalalib.{BoundDep, Dep, DepSyntax, Lib, ScalaModule}
import mill.util.CoursierSupport
import mill.util.Util.millProjectModule
import mill.scalalib.api.{CompilationResult, Versions, JvmWorkerUtil}
import mill.main.client.OutFiles._
import mill.main.client.CodeGenConstants.buildFileExtensions
import mill.main.{BuildInfo, RootModule}

import scala.util.Try

/**
 * Mill module for pre-processing a Mill `build.mill` and related files and then
 * compiling them as a normal [[ScalaModule]]. Parses `build.mill`, walks any
 * `import $file`s, wraps the script files to turn them into valid Scala code
 * and then compiles them with the `ivyDeps` extracted from the `import $ivy`
 * calls within the scripts.
 */
@internal
abstract class MillBuildRootModule()(implicit
    rootModuleInfo: RootModule.Info
) extends RootModule() with ScalaModule {
  override def bspDisplayName0: String = rootModuleInfo
    .projectRoot
    .relativeTo(rootModuleInfo.topLevelProjectRoot)
    .segments
    .++(super.bspDisplayName0.split("/"))
    .mkString("/")

  override def millSourcePath: os.Path = rootModuleInfo.projectRoot / os.up / millBuild
  override def intellijModulePath: os.Path = moduleDir / os.up

  override def scalaVersion: T[String] = BuildInfo.scalaVersion

  /**
   * All script files (that will get wrapped later)
   * @see [[generateScriptSources]]
   */
  def scriptSources: Task.Simple[Seq[PathRef]] = Task.Sources {
    MillBuildRootModule.parseBuildFiles(rootModuleInfo)
      .seenScripts
      .keys
      .toSeq
      .sorted // Ensure ordering is deterministic
      .map(PathRef(_))
  }

  def parseBuildFiles: T[FileImportGraph] = Task {
    scriptSources()
    MillBuildRootModule.parseBuildFiles(rootModuleInfo)
  }

  override def repositoriesTask: Task[Seq[Repository]] = {
    val importedRepos = Task.Anon {
      val repos = parseBuildFiles().repos.map { case (repo, srcFile) =>
        val relFile = Try {
          srcFile.relativeTo(Task.workspace)
        }.recover { case _ => srcFile }.get
        CoursierSupport.repoFromString(
          repo,
          s"buildfile `${relFile}`: import $$repo.`${repo}`"
        )
      }
      repos.find(_.asSuccess.isEmpty) match {
        case Some(error) => error
        case None =>
          val res = repos.flatMap(_.asSuccess).map(_.value).flatten
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
    Agg.from(
      MillIvy.processMillIvyDepSignature(parseBuildFiles().ivyDeps)
        .map(mill.scalalib.Dep.parse)
    ) ++
      Agg(mvn"com.lihaoyi::mill-moduledefs:${Versions.millModuledefsVersion}")
  }

  override def runIvyDeps = Task {
    val imports = cliImports()
    val ivyImports = imports.collect {
      case s"ivy:$rest" => rest
      case s"mvn:$rest" => rest
    }
    Agg.from(
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
        scriptSources(),
        parsed.seenScripts,
        Task.dest,
        rootModuleInfo.enclosingClasspath,
        rootModuleInfo.topLevelProjectRoot,
        rootModuleInfo.output
      )
      Result.Success(Seq(PathRef(Task.dest)))
    }
  }

  def methodCodeHashSignatures: T[Map[String, Int]] = Task(persistent = true) {
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
          // the call graph. We can fo this because we assume `def` Targets are pure,
          // and so any changes in their behavior will be picked up by the runtime build
          // graph evaluator without needing to be accounted for in the post-compile
          // bytecode callgraph analysis.
          def isSimpleTarget =
            (calledSig.desc.ret.pretty == classOf[mill.define.Task.Simple[_]].getName ||
              calledSig.desc.ret.pretty == classOf[mill.define.Worker[_]].getName) &&
              calledSig.desc.args.isEmpty

          // We avoid ignoring method calls that are simple trait forwarders, because
          // we need the trait forwarders calls to be counted in order to wire up the
          // method definition that a Target is associated with during evaluation
          // (e.g. `myModuleObject.myTarget`) with its implementation that may be defined
          // somewhere else (e.g. `trait MyModuleTrait{ def myTarget }`). Only that one
          // step is necessary, after that the runtime build graph invalidation logic can
          // take over
          def isForwarderCallsite =
            callSiteOpt.nonEmpty &&
              callSiteOpt.get.sig.name == (calledSig.name + "$") &&
              callSiteOpt.get.sig.static &&
              callSiteOpt.get.sig.desc.args.size == 1

          // We ignore Commands for the same reason as we ignore Targets, and also because
          // their implementations get gathered up all the via the `Discover` macro, but this
          // is primarily for use as external entrypoints and shouldn't really be counted as
          // part of the `millbuild.build#<init>` transitive call graph they would normally
          // be counted as
          def isCommand =
            calledSig.desc.ret.pretty == classOf[mill.define.Command[_]].getName

          // Skip calls to `millDiscover`. `millDiscover` is bundled as part of `RootModule` for
          // convenience, but it should really never be called by any normal Mill module/task code,
          // and is only used by downstream code in `mill.eval`/`mill.resolve`. Thus although CodeSig's
          // conservative analysis considers potential calls from `build_.package_$#<init>` to
          // `millDiscover()`, we can safely ignore that possibility
          def isMillDiscover = calledSig.name == "millDiscover$lzycompute"

          (isSimpleTarget && !isForwarderCallsite) || isCommand || isMillDiscover
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
    val candidates = Lib.findSourceFiles(allSources(), Seq("scala", "java") ++ buildFileExtensions)
    // We need to unlist those files, which we replaced by generating wrapper scripts
    val filesToExclude = Lib.findSourceFiles(scriptSources(), buildFileExtensions)
    candidates.filterNot(filesToExclude.contains).map(PathRef(_))
  }

  def enclosingClasspath: Task.Simple[Seq[PathRef]] = Task.Sources {
    rootModuleInfo.enclosingClasspath.map(p => mill.api.PathRef(p, quick = true))
  }

  /**
   * Dependencies, which should be transitively excluded.
   * By default, these are the dependencies, which Mill provides itself (via [[unmanagedClasspath]]).
   * We exclude them to avoid incompatible or duplicate artifacts on the classpath.
   */
  protected def resolveDepsExclusions: T[Seq[(String, String)]] = Task {
    val allMillDistModules = BuildInfo.millEmbeddedDeps
      .split(',')
      .filter(_.nonEmpty)
      .map { str =>
        str.split(":", 3) match {
          case Array(org, name, _) => (org, name)
          case other =>
            sys.error(
              s"Unexpected misshapen entry in BuildInfo.millEmbeddedDeps ('$str', expected 'org:name')"
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
    super.bindDependency().apply(dep).exclude(resolveDepsExclusions(): _*)
  }

  override def unmanagedClasspath: T[Agg[PathRef]] = Task {
    enclosingClasspath() ++ lineNumberPluginClasspath()
  }

  override def scalacPluginIvyDeps: T[Agg[Dep]] = Agg(
    mvn"com.lihaoyi:::scalac-mill-moduledefs-plugin:${Versions.millModuledefsVersion}"
  )

  override def scalacOptions: T[Seq[String]] = Task {
    super.scalacOptions() ++
      Seq(
        "-Xplugin:" + lineNumberPluginClasspath().map(_.path).mkString(","),
        "-deprecation",
        // Make sure we abort of the plugin is not found, to ensure any
        // classpath/plugin-discovery issues are surfaced early rather than
        // after hours of debugging
        "-Xplugin-require:mill-linenumber-plugin",
        "-Xplugin-require:auto-override-plugin"
      )
  }

  override def scalacPluginClasspath: T[Agg[PathRef]] =
    super.scalacPluginClasspath() ++ lineNumberPluginClasspath()

  override protected def semanticDbPluginClasspath: T[Agg[PathRef]] =
    super.semanticDbPluginClasspath() ++ lineNumberPluginClasspath()

  def lineNumberPluginClasspath: T[Agg[PathRef]] = Task {
    millProjectModule("mill-runner-linenumbers", repositoriesTask())
  }

  /** Used in BSP IntelliJ, which can only work with directories */
  def dummySources: Sources = Task.Sources(Task.dest)

  def millVersion: Task.Simple[String] = Task.Input { BuildInfo.millVersion }

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
        sources = Agg.from(allSourceFiles().map(_.path)),
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

  class BootstrapModule()(implicit rootModuleInfo: RootModule.Info) extends MillBuildRootModule() {
    override lazy val millDiscover: Discover = Discover[this.type]
  }

  case class Info(
      enclosingClasspath: Seq[os.Path],
      projectRoot: os.Path,
      output: os.Path,
      topLevelProjectRoot: os.Path
  )

  def parseBuildFiles(millBuildRootModuleInfo: RootModule.Info): FileImportGraph = {
    FileImportGraph.parseBuildFiles(
      millBuildRootModuleInfo.topLevelProjectRoot,
      millBuildRootModuleInfo.projectRoot / os.up,
      millBuildRootModuleInfo.output
    )
  }
}
