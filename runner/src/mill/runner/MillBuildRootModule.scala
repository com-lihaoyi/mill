package mill.runner

import coursier.Repository
import mill._
import mill.api.{PathRef, Result, internal}
import mill.define.{Discover, Task}
import mill.scalalib.{BoundDep, Dep, DepSyntax, Lib, ScalaModule}
import mill.util.CoursierSupport
import mill.util.Util.millProjectModule
import mill.scalalib.api.Versions
import pprint.Util.literalize
import FileImportGraph.backtickWrap
import mill.main.client.OutFiles._
import mill.main.{BuildInfo, RootModule}

import scala.collection.immutable.SortedMap
import scala.util.Try

/**
 * Mill module for pre-processing a Mill `build.sc` and related files and then
 * compiling them as a normal [[ScalaModule]]. Parses `build.sc`, walks any
 * `import $file`s, wraps the script files to turn them into valid Scala code
 * and then compiles them with the `ivyDeps` extracted from the `import $ivy`
 * calls within the scripts.
 *
 * Also dumps the [[scriptImportGraph]] for the downstream Evaluator to use for
 * fine-grained task invalidation based the import relationship between the file
 * defining the task and any files which were changed.
 */
@internal
class MillBuildRootModule()(implicit
    baseModuleInfo: RootModule.Info,
    millBuildRootModuleInfo: MillBuildRootModule.Info
) extends RootModule.Base() with ScalaModule {
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
  def scriptSources = T.sources {
    MillBuildRootModule.parseBuildFiles(millBuildRootModuleInfo)
      .seenScripts
      .keys.map(PathRef(_))
      .toSeq
  }

  def parseBuildFiles: T[FileImportGraph] = T {
    scriptSources()
    MillBuildRootModule.parseBuildFiles(millBuildRootModuleInfo)
  }

  override def repositoriesTask: Task[Seq[Repository]] = {
    val importedRepos = T.task {
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

    T.task {
      super.repositoriesTask() ++ importedRepos()
    }
  }

  def cliImports: Target[Seq[String]] = T.input {
    val imports = CliImports.value
    if (imports.nonEmpty) {
      T.log.debug(s"Using cli-provided runtime imports: ${imports.mkString(", ")}")
    }
    imports
  }

  override def ivyDeps = T {
    Agg.from(
      MillIvy.processMillIvyDepSignature(parseBuildFiles().ivyDeps)
        .map(mill.scalalib.Dep.parse)
    ) ++
      Agg(ivy"com.lihaoyi::mill-moduledefs:${Versions.millModuledefsVersion}")
  }

  override def runIvyDeps = T {
    val imports = cliImports()
    val ivyImports = imports.collect { case s"ivy:$rest" => rest }
    Agg.from(
      MillIvy.processMillIvyDepSignature(ivyImports.toSet)
        .map(mill.scalalib.Dep.parse)
    )
  }

  override def platformSuffix: T[String] = s"_mill${BuildInfo.millBinPlatform}"

  override def generatedSources: T[Seq[PathRef]] = T {
    generateScriptSources()
  }

  def generateScriptSources: T[Seq[PathRef]] = T {
    val parsed = parseBuildFiles()
    if (parsed.errors.nonEmpty) Result.Failure(parsed.errors.mkString("\n"))
    else {
      MillBuildRootModule.generateWrappedSources(
        millBuildRootModuleInfo.projectRoot / os.up,
        scriptSources(),
        parsed.seenScripts,
        T.dest,
        millBuildRootModuleInfo.enclosingClasspath,
        millBuildRootModuleInfo.topLevelProjectRoot
      )
      Result.Success(Seq(PathRef(T.dest)))
    }
  }

  def scriptImportGraph: T[Map[os.Path, (Int, Seq[os.Path])]] = T {
    parseBuildFiles()
      .importGraphEdges
      .map { case (path, imports) =>
        (path, (PathRef(path).hashCode(), imports))
      }
  }

  def methodCodeHashSignatures: T[Map[String, Int]] = T.persistent {
    os.remove.all(T.dest / "previous")
    if (os.exists(T.dest / "current")) os.move.over(T.dest / "current", T.dest / "previous")
    val debugEnabled = T.log.debugEnabled
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
            (calledSig.desc.ret.pretty == classOf[mill.define.Target[_]].getName ||
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

          (isSimpleTarget && !isForwarderCallsite) || isCommand
        },
        logger = new mill.codesig.Logger(Option.when(debugEnabled)(T.dest / "current")),
        prevTransitiveCallGraphHashesOpt = () =>
          Option.when(os.exists(T.dest / "previous" / "result.json"))(
            upickle.default.read[Map[String, Int]](
              os.read.stream(T.dest / "previous" / "result.json")
            )
          )
      )

    val result = codesig.transitiveCallGraphHashes
    if (debugEnabled) {
      os.write(
        T.dest / "current" / "result.json",
        upickle.default.stream(
          SortedMap.from(codesig.transitiveCallGraphHashes0.map { case (k, v) => (k.toString, v) }),
          indent = 4
        )
      )
    }
    result
  }

  override def sources: T[Seq[PathRef]] = T {
    scriptSources() ++ {
      if (parseBuildFiles().millImport) super.sources()
      else Seq.empty[PathRef]
    }
  }

  override def resources: T[Seq[PathRef]] = T {
    if (parseBuildFiles().millImport) super.resources()
    else Seq.empty[PathRef]
  }

  override def allSourceFiles: T[Seq[PathRef]] = T {
    val candidates = Lib.findSourceFiles(allSources(), Seq("scala", "java", "sc"))
    // We need to unlist those files, which we replaced by generating wrapper scripts
    val filesToExclude = Lib.findSourceFiles(scriptSources(), Seq("sc"))
    candidates.filterNot(filesToExclude.contains).map(PathRef(_))
  }

  def enclosingClasspath = T.sources {
    millBuildRootModuleInfo.enclosingClasspath.map(p => mill.api.PathRef(p, quick = true))
  }

  /**
   * Dependencies, which should be transitively excluded.
   * By default, these are the dependencies, which Mill provides itself (via [[unmanagedClasspath]]).
   * We exclude them to avoid incompatible or duplicate artifacts on the classpath.
   */
  protected def resolveDepsExclusions: T[Seq[(String, String)]] = T {
    Lib.millAssemblyEmbeddedDeps.toSeq.map(d =>
      (d.dep.module.organization.value, d.dep.module.name.value)
    )
  }

  override def bindDependency: Task[Dep => BoundDep] = T.task { dep: Dep =>
    super.bindDependency().apply(dep).exclude(resolveDepsExclusions(): _*)
  }

  override def unmanagedClasspath: T[Agg[PathRef]] = T {
    enclosingClasspath() ++ lineNumberPluginClasspath()
  }

  override def scalacPluginIvyDeps: T[Agg[Dep]] = Agg(
    ivy"com.lihaoyi:::scalac-mill-moduledefs-plugin:${Versions.millModuledefsVersion}"
  )

  override def scalacOptions: T[Seq[String]] = T {
    super.scalacOptions() ++
      Seq(
        "-Xplugin:" + lineNumberPluginClasspath().map(_.path).mkString(","),
        "-deprecation",
        // Make sure we abort of the plugin is not found, to ensure any
        // classpath/plugin-discovery issues are surfaced early rather than
        // after hours of debugging
        "-Xplugin-require:mill-linenumber-plugin"
      )
  }

  override def scalacPluginClasspath: T[Agg[PathRef]] =
    super.scalacPluginClasspath() ++ lineNumberPluginClasspath()

  def lineNumberPluginClasspath: T[Agg[PathRef]] = T {
    millProjectModule("mill-runner-linenumbers", repositoriesTask())
  }

  /** Used in BSP IntelliJ, which can only work with directories */
  def dummySources: Sources = T.sources(T.dest)
}

object MillBuildRootModule {

  class BootstrapModule(
      topLevelProjectRoot0: os.Path,
      projectRoot: os.Path,
      enclosingClasspath: Seq[os.Path]
  )(implicit baseModuleInfo: RootModule.Info) extends RootModule.Base {

    implicit private def millBuildRootModuleInfo: Info = MillBuildRootModule.Info(
      enclosingClasspath,
      projectRoot,
      topLevelProjectRoot0
    )
    object build extends MillBuildRootModule

    override lazy val millDiscover: Discover[this.type] =
      baseModuleInfo.discover.asInstanceOf[Discover[this.type]]
  }

  case class Info(
      enclosingClasspath: Seq[os.Path],
      projectRoot: os.Path,
      topLevelProjectRoot: os.Path
  )

  def parseBuildFiles(millBuildRootModuleInfo: MillBuildRootModule.Info): FileImportGraph = {
    FileImportGraph.parseBuildFiles(
      millBuildRootModuleInfo.topLevelProjectRoot,
      millBuildRootModuleInfo.projectRoot / os.up
    )
  }

  def generateWrappedSources(
      base: os.Path,
      scriptSources: Seq[PathRef],
      scriptCode: Map[os.Path, String],
      targetDest: os.Path,
      enclosingClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path
  ): Unit = {
    for (scriptSource <- scriptSources) {
      val relative = scriptSource.path.relativeTo(base)
      val dest = targetDest / FileImportGraph.fileImportToSegments(base, scriptSource.path, false)

      val pkg0 = FileImportGraph.fileImportToSegments(base, scriptSource.path, true).dropRight(1)
      val specialNames = Set("build", "module")
      val (pkg, newSource) =
        if (specialNames(scriptSource.path.baseName)) {
          val pkg :+ pkgLast = pkg0

          pkg -> topBuild(
            relative.segments.init,
            scriptSource.path / os.up,
            pkgLast,
            enclosingClasspath,
            millTopLevelProjectRoot
          )
        } else {
          val pkg = pkg0
          pkg -> s"""object ${backtickWrap(scriptSource.path.baseName)} {"""
        }

      val pkgLine = pkg.map(p => "package " + backtickWrap(p)).mkString("\n")
      val markerComment =
        s"""//MILL_ORIGINAL_FILE_PATH=${scriptSource.path}
           |//MILL_USER_CODE_START_MARKER""".stripMargin

      os.write(
        dest,
        Seq(
          pkgLine,
          newSource,
          markerComment,
          scriptCode(scriptSource.path),
          MillBuildRootModule.bottom
        ).mkString("\n"),
        createFolders = true
      )
    }
  }

  def topBuild(
      segs: Seq[String],
      base: os.Path,
      name: String,
      enclosingClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path
  ): String = {
    val segsList = segs.map(pprint.Util.literalize(_)).mkString(", ")
    val superClass = if (name == "build") "Base" else "Foreign"

    s"""import _root_.mill.runner.MillBuildRootModule
       |package ${backtickWrap(name)}{
       |  @_root_.scala.annotation.nowarn
       |  object MillMiscInfo extends MillBuildRootModule.MillMiscInfo(
       |    ${enclosingClasspath.map(p => literalize(p.toString))},
       |    ${literalize(base.toString)},
       |    ${literalize(millTopLevelProjectRoot.toString)},
       |    _root_.mill.define.Discover[${backtickWrap(name + "_")}]
       |  )
       |}
       |
       |import ${backtickWrap(name)}.MillMiscInfo._
       |
       |package object ${backtickWrap(name)} extends ${backtickWrap(name + "_")}
       |
       |class ${backtickWrap(name + "_")}
       |extends _root_.mill.main.RootModule.$superClass($segsList) {
       |""".stripMargin
  }

  val bottom = "\n}"

  class MillMiscInfo(
      enclosingClasspath: Seq[String],
      projectRoot: String,
      topLevelProjectRoot: String,
      discover: Discover[_]
  ) {
    implicit val millBuildRootModuleInfo: MillBuildRootModule.Info = MillBuildRootModule.Info(
      enclosingClasspath.map(os.Path(_)),
      os.Path(projectRoot),
      os.Path(topLevelProjectRoot)
    )
    implicit val millBaseModuleInfo: RootModule.Info = RootModule.Info(
      millBuildRootModuleInfo.projectRoot,
      discover
    )
  }

}
