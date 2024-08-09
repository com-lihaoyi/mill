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
import mill.main.{BuildInfo, RootModule}

import scala.collection.immutable.SortedMap
import scala.util.Try
import mill.define.Target

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

  override def millSourcePath: os.Path = millBuildRootModuleInfo.projectRoot / os.up / "mill-build"
  override def intellijModulePath: os.Path = millSourcePath / os.up

  override def scalaVersion: T[String] = BuildInfo.scalaVersion

  /**
   * All script files (that will get wrapped later)
   * @see [[generateScriptSources]]
   */
  def scriptSources: Target[Seq[PathRef]] = Task.sources {
    MillBuildRootModule.parseBuildFiles(millBuildRootModuleInfo)
      .seenScripts
      .keys.map(PathRef(_))
      .toSeq
  }

  def parseBuildFiles: T[FileImportGraph] = Task {
    scriptSources()
    MillBuildRootModule.parseBuildFiles(millBuildRootModuleInfo)
  }

  override def repositoriesTask: Task[Seq[Repository]] = {
    val importedRepos = Task.anon {
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

    Task.anon {
      super.repositoriesTask() ++ importedRepos()
    }
  }

  def cliImports: Target[Seq[String]] = Task.input {
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
      MillBuildRootModule.generateWrappedSources(
        millBuildRootModuleInfo.projectRoot / os.up,
        scriptSources(),
        parsed.seenScripts,
        Task.dest,
        millBuildRootModuleInfo.enclosingClasspath,
        millBuildRootModuleInfo.topLevelProjectRoot
      )
      Result.Success(Seq(PathRef(Task.dest)))
    }
  }

  def scriptImportGraph: T[Map[os.Path, (Int, Seq[os.Path])]] = Task {
    parseBuildFiles()
      .importGraphEdges
      .map { case (path, imports) =>
        (path, (PathRef(path).hashCode(), imports))
      }
  }

  def methodCodeHashSignatures: T[Map[String, Int]] = Task.persistent {
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
        logger = new mill.codesig.Logger(Option.when(debugEnabled)(Task.dest / "current")),
        prevTransitiveCallGraphHashesOpt = () =>
          Option.when(os.exists(Task.dest / "previous" / "result.json"))(
            upickle.default.read[Map[String, Int]](
              os.read.stream(Task.dest / "previous" / "result.json")
            )
          )
      )

    val result = codesig.transitiveCallGraphHashes
    if (debugEnabled) {
      os.write(
        Task.dest / "current" / "result.json",
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
    val candidates = Lib.findSourceFiles(allSources(), Seq("scala", "java", "sc"))
    // We need to unlist those files, which we replaced by generating wrapper scripts
    val filesToExclude = Lib.findSourceFiles(scriptSources(), Seq("sc"))
    candidates.filterNot(filesToExclude.contains).map(PathRef(_))
  }

  def enclosingClasspath: Target[Seq[PathRef]] = Task.sources {
    millBuildRootModuleInfo.enclosingClasspath.map(p => mill.api.PathRef(p, quick = true))
  }

  /**
   * Dependencies, which should be transitively excluded.
   * By default, these are the dependencies, which Mill provides itself (via [[unmanagedClasspath]]).
   * We exclude them to avoid incompatible or duplicate artifacts on the classpath.
   */
  protected def resolveDepsExclusions: T[Seq[(String, String)]] = Task {
    Lib.millAssemblyEmbeddedDeps.toSeq.map(d =>
      (d.dep.module.organization.value, d.dep.module.name.value)
    )
  }

  override def bindDependency: Task[Dep => BoundDep] = Task.anon { dep: Dep =>
    super.bindDependency().apply(dep).exclude(resolveDepsExclusions(): _*)
  }

  override def unmanagedClasspath: T[Agg[PathRef]] = Task {
    enclosingClasspath() ++ lineNumberPluginClasspath()
  }

  override def scalacPluginIvyDeps: T[Agg[Dep]] = Agg(
    ivy"com.lihaoyi:::scalac-mill-moduledefs-plugin:${Versions.millModuledefsVersion}"
  )

  override def scalacOptions: T[Seq[String]] = Task {
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

  def lineNumberPluginClasspath: T[Agg[PathRef]] = Task {
    millProjectModule("mill-runner-linenumbers", repositoriesTask())
  }

  /** Used in BSP IntelliJ, which can only work with directories */
  def dummySources: Sources = Task.sources(Task.dest)
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

      val pkg = FileImportGraph.fileImportToSegments(base, scriptSource.path, true).dropRight(1)
      val specialNames = Set("build", "module")
      val newSource = MillBuildRootModule.top(
        specialNames(scriptSource.path.baseName),
        if (specialNames(scriptSource.path.baseName)) relative.segments.init
        else relative.segments.init :+ relative.baseName,
        scriptSource.path / os.up,
        if (specialNames(scriptSource.path.baseName)) pkg.dropRight(1) else pkg,
        if (specialNames(scriptSource.path.baseName)) pkg.last else scriptSource.path.baseName,
        enclosingClasspath,
        millTopLevelProjectRoot,
        scriptSource.path
      ) +
        scriptCode(scriptSource.path) +
        MillBuildRootModule.bottom

      os.write(dest, newSource, createFolders = true)
    }
  }

  def top(
      isBuildOrModuleSc: Boolean,
      segs: Seq[String],
      base: os.Path,
      pkg: Seq[String],
      name: String,
      enclosingClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      originalFilePath: os.Path
  ): String = {
    val segsList = segs.map(pprint.Util.literalize(_)).mkString(", ")
    val superClass =
      if (name == "build") {
        s"_root_.mill.main.RootModule.Base(Some(_root_.mill.define.Segments.labels($segsList)))"
      } else {
        s"_root_.mill.main.RootModule.Foreign(Some(_root_.mill.define.Segments.labels($segsList)))"
      }

    val imports = ""
//      if (name == "build") "import mill.main.RootModule"
//      else "import mill.main.{RootModuleForeign => RootModule}"

    val miscInfoName = s"MiscInfo_$name"

    val pkgLine = pkg.map(p => "package " + backtickWrap(p)).mkString("\n")

    if (isBuildOrModuleSc) {
      s"""$pkgLine
         |
         |import _root_.mill.runner.MillBuildRootModule
         |$imports
         |
         |object ${backtickWrap(miscInfoName)} {
         |  implicit lazy val millBuildRootModuleInfo: _root_.mill.runner.MillBuildRootModule.Info = _root_.mill.runner.MillBuildRootModule.Info(
         |    ${enclosingClasspath.map(p => literalize(p.toString))}.map(_root_.os.Path(_)),
         |    _root_.os.Path(${literalize(base.toString)}),
         |    _root_.os.Path(${literalize(millTopLevelProjectRoot.toString)}),
         |  )
         |  implicit lazy val millBaseModuleInfo: _root_.mill.main.RootModule.Info = _root_.mill.main.RootModule.Info(
         |    millBuildRootModuleInfo.projectRoot,
         |    _root_.mill.define.Discover[${backtickWrap(name + "_")}]
         |  )
         |}
         |import ${backtickWrap(miscInfoName)}.{millBuildRootModuleInfo, millBaseModuleInfo}
         |package object ${backtickWrap(name)} extends ${backtickWrap(name + "_")}
         |import ${backtickWrap(name)}._
         |class ${backtickWrap(name + "_")} extends $superClass {
         |
         |//MILL_ORIGINAL_FILE_PATH=${originalFilePath}
         |//MILL_USER_CODE_START_MARKER
         |""".stripMargin
    } else {
      s"""$pkgLine
         |object ${backtickWrap(name)} {
         |
         |//MILL_ORIGINAL_FILE_PATH=${originalFilePath}
         |//MILL_USER_CODE_START_MARKER
         |""".stripMargin
    }
  }

  val bottom = "\n}"
}
