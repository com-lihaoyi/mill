package mill.runner.meta
import scala.jdk.CollectionConverters.ListHasAsScala
import coursier.Repository
import mill.*
import mill.api.Result
import mill.define.{PathRef, Discover, Task}
import mill.scalalib.{BoundDep, Dep, DepSyntax, Lib, ScalaModule}
import mill.util.Jvm
import mill.scalalib.api.JvmWorkerUtil
import mill.scalalib.api.{CompilationResult, Versions}
import mill.constants.OutFiles.*
import mill.constants.CodeGenConstants.buildFileExtensions
import mill.util.BuildInfo
import mill.define.RootModule0
import mill.runner.meta.ScalaCompilerWorker
import mill.runner.worker.api.ScalaCompilerWorkerApi

import scala.util.Try
import mill.define.Target
import mill.api.Watchable
import mill.api.internal.internal
import mill.runner.worker.api.MillScalaParser

import scala.collection.mutable

/**
 * Mill module for pre-processing a Mill `build.mill` and related files and then
 * compiling them as a normal [[ScalaModule]]. Parses `build.mill`, walks any
 * `import $file`s, wraps the script files to turn them into valid Scala code
 * and then compiles them with the `mvnDeps` extracted from the `import $ivy`
 * calls within the scripts.
 */
@internal
class MillBuildRootModule()(implicit
    rootModuleInfo: RootModule0.Info,
    scalaCompilerResolver: ScalaCompilerWorker.Resolver
) extends mill.main.MainRootModule() with ScalaModule {
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

  override def mandatoryMvnDeps = Task {
    Seq.from(
      MillIvy.processMillMvnDepsignature(parseBuildFiles().mvnDeps)
        .map(mill.scalalib.Dep.parse)
    ) ++
      Seq(
        mvn"com.lihaoyi::mill-main:${Versions.millVersion}"
      ) ++
      // only include mill-runner for meta-builds
      Option.when(rootModuleInfo.projectRoot / os.up != rootModuleInfo.topLevelProjectRoot) {
        mvn"com.lihaoyi::mill-runner-meta:${Versions.millVersion}"
      }
  }

  override def runMvnDeps = Task {
    val imports = cliImports()
    val ivyImports = imports.collect { case s"ivy:$rest" => rest }
    Seq.from(
      MillIvy.processMillMvnDepsignature(ivyImports.toSet)
        .map(mill.scalalib.Dep.parse)
    ) ++ Seq(
      // Needed at runtime to insantiate a `mill.eval.EvaluatorImpl` in the `build.mill`,
      // classloader but should not be available for users to compile against
      mvn"com.lihaoyi::mill-core-eval:${Versions.millVersion}"
    )
  }

  override def platformSuffix: T[String] = s"_mill${BuildInfo.millBinPlatform}"

  override def generatedSources: T[Seq[PathRef]] = Task {
    generateScriptSources()
  }

  def millBuildRootModuleResult = Task {
    Tuple3(
      runClasspath().map(_.path.toNIO.toString),
      compile().classes.path.toNIO.toString,
      codeSignatures()
    )
  }
  def generateScriptSources: T[Seq[PathRef]] = Task {
    val parsed = parseBuildFiles()
    if (parsed.errors.nonEmpty) Result.Failure(parsed.errors.mkString("\n"))
    else {
      CodeGen.generateWrappedSources(
        rootModuleInfo.projectRoot / os.up,
        parsed.seenScripts,
        Task.dest,
        rootModuleInfo.compilerWorkerClasspath,
        rootModuleInfo.topLevelProjectRoot,
        rootModuleInfo.output,
        compilerWorker()
      )
      Result.Success(Seq(PathRef(Task.dest)))
    }
  }

  def codeSignatures: T[Map[String, Int]] = Task {
    val (analysisFolder, _) = callGraphAnalysis()
    val transitiveCallGraphHashes0 = upickle.default.read[Map[String, Int]](
      os.read.stream(analysisFolder / "transitiveCallGraphHashes0.json")
    )
    transitiveCallGraphHashes0
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

  override def unmanagedClasspath: T[Seq[PathRef]] = Task.Input {
    Option(System.getenv("MILL_LOCAL_TEST_OVERRIDE_CLASSPATH"))
      .map(s => PathRef(os.Path(s)))
      .toSeq
  }

  def compileMvnDeps = Seq(
    mvn"com.lihaoyi::sourcecode:0.4.3-M5"
  )
  override def scalacPluginMvnDeps: T[Seq[Dep]] = Seq(
    mvn"com.lihaoyi:::scalac-mill-moduledefs-plugin:${Versions.millModuledefsVersion}"
      .exclude("com.lihaoyi" -> "sourcecode_3")
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
      rootModuleInfo: RootModule0.Info,
      scalaCompilerResolver: ScalaCompilerWorker.Resolver
  ) extends MillBuildRootModule() {
    override lazy val millDiscover = Discover[this.type]
  }

  case class Info(
      projectRoot: os.Path,
      output: os.Path,
      topLevelProjectRoot: os.Path
  )

  def parseBuildFiles(
      parser: MillScalaParser,
      millBuildRootModuleInfo: RootModule0.Info
  ): FileImportGraph = {
    FileImportGraph.parseBuildFiles(
      parser,
      millBuildRootModuleInfo.topLevelProjectRoot,
      millBuildRootModuleInfo.projectRoot / os.up,
      millBuildRootModuleInfo.output
    )
  }
}
