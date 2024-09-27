package mill.runner

import coursier.Repository
import mill._
import mill.api.{PathRef, Result, internal}
import mill.define.{Discover, Task}
import mill.scalalib.{BoundDep, Dep, DepSyntax, Lib, ScalaModule}
import mill.util.CoursierSupport
import mill.util.Util.millProjectModule
import mill.scalalib.api.Versions
import mill.scalalib.api.ZincWorkerUtil
import mill.main.client.OutFiles._
import mill.main.client.CodeGenConstants.buildFileExtensions
import mill.main.{BuildInfo, RootModule}
import mill.runner.worker.ScalaCompilerWorker
import mill.runner.worker.api.ScalaCompilerWorkerApi

import scala.collection.immutable.SortedMap
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
    baseModuleInfo: RootModule.Info,
    millBuildRootModuleInfo: MillBuildRootModule.Info,
    scalaCompilerResolver: ScalaCompilerWorker.Resolver
) extends RootModule() with ScalaModule /*with WithScalaCompilerWorker*/ {
  override def bspDisplayName0: String = millBuildRootModuleInfo
    .projectRoot
    .relativeTo(millBuildRootModuleInfo.topLevelProjectRoot)
    .segments
    .++(super.bspDisplayName0.split("/"))
    .mkString("/")

  override def millSourcePath: os.Path = millBuildRootModuleInfo.projectRoot / os.up / millBuild
  override def intellijModulePath: os.Path = millSourcePath / os.up

  override def scalaVersion: T[String] = BuildInfo.scalaVersion

  /**
   * All script files (that will get wrapped later)
   * @see [[generateScriptSources]]
   */
  def scriptSources: Target[Seq[PathRef]] = Task.Sources {
    MillBuildRootModule.parseBuildFiles(compilerWorker(), millBuildRootModuleInfo)
      .seenScripts
      .keys.map(PathRef(_))
      .toSeq
  }

  def parseBuildFiles: T[FileImportGraph] = Task {
    scriptSources()
    MillBuildRootModule.parseBuildFiles(compilerWorker(), millBuildRootModuleInfo)
  }

  private[runner] def compilerWorker: Worker[ScalaCompilerWorkerApi] = Task.Worker {
    scalaCompilerResolver.resolve()
  }

  override def repositoriesTask: Task[Seq[Repository]] = {
    val importedRepos = Task.Anon {
      val repos = parseBuildFiles().repos.map { case (repo, srcFile) =>
        val relFile = Try {
          srcFile.relativeTo(T.workspace)
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
      T.log.debug(s"Using cli-provided runtime imports: ${imports.mkString(", ")}")
    }
    imports
  }

  override def ivyDeps = Task {
    Agg.from(
      MillIvy.processMillIvyDepSignature(parseBuildFiles().ivyDeps)
        .map(mill.scalalib.Dep.parse)
    ) ++
      Agg(ivy"com.lihaoyi::mill-moduledefs:${Versions.millModuledefsVersion}")
  }

  override def runIvyDeps = Task {
    val imports = cliImports()
    val ivyImports = imports.collect { case s"ivy:$rest" => rest }
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
        millBuildRootModuleInfo.projectRoot / os.up,
        scriptSources(),
        parsed.seenScripts,
        T.dest,
        millBuildRootModuleInfo.enclosingClasspath,
        scalaCompilerResolver.classpath,
        millBuildRootModuleInfo.topLevelProjectRoot,
        millBuildRootModuleInfo.output,
        ZincWorkerUtil.isScala3(scalaVersion()),
        compilerWorker()
      )
      Result.Success(Seq(PathRef(T.dest)))
    }
  }

  def methodCodeHashSignatures: T[Map[String, Int]] = Task(persistent = true) {
    os.remove.all(T.dest / "previous")
    if (os.exists(T.dest / "current")) os.move.over(T.dest / "current", T.dest / "previous")
    val debugEnabled = T.log.debugEnabled
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
            (desc.ret.pretty == classOf[mill.define.Target[_]].getName ||
              desc.ret.pretty == classOf[mill.define.Worker[_]].getName) &&
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
            calledSig.desc.ret.pretty == classOf[mill.define.Command[_]].getName

          (isSimpleTarget(calledSig.desc) && !isForwarderCallsiteOrLambda) || isCommand
        },
        logger = new mill.codesig.Logger(Option.when(debugEnabled)(T.dest / "current")),
        prevTransitiveCallGraphHashesOpt = () =>
          Option.when(os.exists(T.dest / "previous/result.json"))(
            upickle.default.read[Map[String, Int]](
              os.read.stream(T.dest / "previous/result.json")
            )
          )
      )

    val result = codesig.transitiveCallGraphHashes
    if (debugEnabled) {
      os.write(
        T.dest / "current/result.json",
        upickle.default.stream(
          SortedMap.from(codesig.transitiveCallGraphHashes0.map { case (k, v) => (k.toString, v) }),
          indent = 4
        )
      )
    }
    result
  }

  override def sources: T[Seq[PathRef]] = Task {
    scriptSources() ++ {
      if (parseBuildFiles().millImport) super.sources()
      else Seq.empty[PathRef]
    }
  }

  override def resources: T[Seq[PathRef]] = Task {
    if (parseBuildFiles().millImport) super.resources()
    else Seq.empty[PathRef]
  }

  override def allSourceFiles: T[Seq[PathRef]] = Task {
    val candidates = Lib.findSourceFiles(allSources(), Seq("scala", "java") ++ buildFileExtensions)
    // We need to unlist those files, which we replaced by generating wrapper scripts
    val filesToExclude = Lib.findSourceFiles(scriptSources(), buildFileExtensions.toIndexedSeq)
    candidates.filterNot(filesToExclude.contains).map(PathRef(_))
  }

  def enclosingClasspath: Target[Seq[PathRef]] = Task.Sources {
    millBuildRootModuleInfo.enclosingClasspath.map(p => mill.api.PathRef(p, quick = true))
  }

  /**
   * Dependencies, which should be transitively excluded.
   * By default, these are the dependencies, which Mill provides itself (via [[unmanagedClasspath]]).
   * We exclude them to avoid incompatible or duplicate artifacts on the classpath.
   */
  protected def resolveDepsExclusions: T[Seq[(String, String)]] = Task {
    Lib.millAssemblyEmbeddedDeps.toSeq.flatMap({ d =>
      val isScala3 = ZincWorkerUtil.isScala3(scalaVersion())
      if isScala3 && d.dep.module.name.value == "scala-library" then None
      else Some((d.dep.module.organization.value, d.dep.module.name.value))
    })
  }

  override def bindDependency: Task[Dep => BoundDep] = Task.Anon { (dep: Dep) =>
    super.bindDependency.apply().apply(dep).exclude(resolveDepsExclusions(): _*)
  }

  override def unmanagedClasspath: T[Agg[PathRef]] = Task {
    enclosingClasspath()
  }

  override def scalacPluginIvyDeps: T[Agg[Dep]] = Agg(
    ivy"com.lihaoyi:::scalac-mill-moduledefs-plugin:${Versions.millModuledefsVersion}"
  )

  override def scalacOptions: T[Seq[String]] = Task {
    super.scalacOptions() ++
      Seq(
        "-deprecation"
      )
  }

  override def scalacPluginClasspath: T[Agg[PathRef]] =
    super.scalacPluginClasspath() ++ lineNumberPluginClasspath()

  override protected def semanticDbPluginClasspath: T[Agg[PathRef]] =
    super.semanticDbPluginClasspath() ++ lineNumberPluginClasspath()

  def lineNumberPluginClasspath: T[Agg[PathRef]] = Task {
    // millProjectModule("mill-runner-linenumbers", repositoriesTask())
    Agg.empty
  }

  /** Used in BSP IntelliJ, which can only work with directories */
  def dummySources: Sources = Task.Sources(T.dest)
}

object MillBuildRootModule {

  class BootstrapModule(
      topLevelProjectRoot0: os.Path,
      projectRoot: os.Path,
      output: os.Path,
      enclosingClasspath: Seq[os.Path],
      scalaCompilerWorker: ScalaCompilerWorker.ResolvedWorker
  )(implicit baseModuleInfo: RootModule.Info) extends MillBuildRootModule()(
        implicitly,
        MillBuildRootModule.Info(
          enclosingClasspath,
          projectRoot,
          output,
          topLevelProjectRoot0
        ),
        scalaCompilerWorker.constResolver
      ) {

    override lazy val millDiscover: Discover = baseModuleInfo.discover
  }

  case class Info(
      enclosingClasspath: Seq[os.Path],
      projectRoot: os.Path,
      output: os.Path,
      topLevelProjectRoot: os.Path
  )

  def parseBuildFiles(
      parser: MillScalaParser,
      millBuildRootModuleInfo: MillBuildRootModule.Info
  ): FileImportGraph = {
    FileImportGraph.parseBuildFiles(
      parser,
      millBuildRootModuleInfo.topLevelProjectRoot,
      millBuildRootModuleInfo.projectRoot / os.up,
      millBuildRootModuleInfo.output
    )
  }

  class MillMiscInfo(
      enclosingClasspath: Seq[String],
      compilerWorkerClasspath: Seq[String],
      projectRoot: String,
      output: String,
      topLevelProjectRoot: String,
      segments: Seq[String]
  ) {
    implicit lazy val millBuildRootModuleInfo: MillBuildRootModule.Info = MillBuildRootModule.Info(
      enclosingClasspath.map(os.Path(_)),
      os.Path(projectRoot),
      os.Path(output),
      os.Path(topLevelProjectRoot)
    )
    implicit lazy val millRunnerScalaCompilerWorker: ScalaCompilerWorker.Resolver = {
      ScalaCompilerWorker.Resolver(
        compilerWorkerClasspath.map(os.Path(_)),
        (home, cp) => ScalaCompilerWorker.reflect(cp)(using home)
      )
    }
    implicit lazy val millBaseModuleInfo: RootModule.Info = RootModule.Info(
      millBuildRootModuleInfo.projectRoot,
      null
    )
    implicit lazy val subfolderInfo: RootModule.SubFolderInfo = RootModule.SubFolderInfo(
      segments
    )
  }
}
