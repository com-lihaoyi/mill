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
import mill.main.BuildInfo

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
    millBuildRootModuleInfo0: MillBuildRootModule.Info
) extends RootModule() with ScalaModule {
  def millBuildRootModuleInfo = T.input { millBuildRootModuleInfo0 }
  override def bspDisplayName0: String = millBuildRootModuleInfo0
    .projectRoot
    .relativeTo(millBuildRootModuleInfo0.topLevelProjectRoot)
    .segments
    .++(super.bspDisplayName0.split("/"))
    .mkString("/")

  override def millSourcePath = millBuildRootModuleInfo0.projectRoot / os.up / "mill-build"
  override def intellijModulePath: os.Path = millSourcePath / os.up

  override def scalaVersion: T[String] = "2.13.10"

  /**
   * All script files (that will get wrapped later)
   * @see [[generateScriptSources]]
   */
  def scriptSources = T.sources {
    MillBuildRootModule.parseBuildFiles(millBuildRootModuleInfo())
      .seenScripts
      .keys.map(PathRef(_))
      .toSeq
  }

  def parseBuildFiles: T[FileImportGraph] = T {
    scriptSources()
    MillBuildRootModule.parseBuildFiles(millBuildRootModuleInfo())
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

  def cliImports = T { millBuildRootModuleInfo().cliImports }

  override def ivyDeps = T {
    Agg.from(
      MillIvy.processMillIvyDepSignature(parseBuildFiles().ivyDeps)
        .map(mill.scalalib.Dep.parse)
    ) ++
      Seq(ivy"com.lihaoyi::mill-moduledefs:${Versions.millModuledefsVersion}")
  }

  override def runIvyDeps = T {
    Agg.from(
      MillIvy.processMillIvyDepSignature(cliImports().toSet)
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
        millBuildRootModuleInfo().projectRoot / os.up,
        scriptSources(),
        parsed.seenScripts,
        T.dest,
        millBuildRootModuleInfo().enclosingClasspath,
        millBuildRootModuleInfo().topLevelProjectRoot,
        millBuildRootModuleInfo().cliImports
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
    millBuildRootModuleInfo().enclosingClasspath.map(p => mill.api.PathRef(p, quick = true))
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
      enclosingClasspath: Seq[os.Path],
      cliImports: Seq[String]
  )(implicit baseModuleInfo: RootModule.Info) extends RootModule {

    implicit private def millBuildRootModuleInfo: Info = MillBuildRootModule.Info(
      enclosingClasspath,
      projectRoot,
      topLevelProjectRoot0,
      cliImports
    )
    object build extends MillBuildRootModule

    override lazy val millDiscover: Discover[this.type] =
      baseModuleInfo.discover.asInstanceOf[Discover[this.type]]
  }

  case class Info(
      enclosingClasspath: Seq[os.Path],
      projectRoot: os.Path,
      topLevelProjectRoot: os.Path,
      cliImports: Seq[String]
  )
  object Info {
    implicit val rw: upickle.default.ReadWriter[Info] = upickle.default.macroRW
  }

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
      millTopLevelProjectRoot: os.Path,
      cliImports: Seq[String]
  ): Unit = {
    for (scriptSource <- scriptSources) {
      val relative = scriptSource.path.relativeTo(base)
      val dest = targetDest / FileImportGraph.fileImportToSegments(base, scriptSource.path, false)

      val newSource = MillBuildRootModule.top(
        relative,
        scriptSource.path / os.up,
        FileImportGraph.fileImportToSegments(base, scriptSource.path, true).dropRight(1),
        scriptSource.path.baseName,
        enclosingClasspath,
        millTopLevelProjectRoot,
        scriptSource.path,
        cliImports
      ) +
        scriptCode(scriptSource.path) +
        MillBuildRootModule.bottom

      os.write(dest, newSource, createFolders = true)
    }
  }

  def top(
      relative: os.RelPath,
      base: os.Path,
      pkg: Seq[String],
      name: String,
      enclosingClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      originalFilePath: os.Path,
      cliImports: Seq[String]
  ): String = {
    val superClass =
      if (pkg.size <= 1 && name == "build") "_root_.mill.main.RootModule"
      else {
        // Computing a path in "out" that uniquely reflects the location
        // of the foreign module relatively to the current build.

        // Encoding the number of `/..`
        val ups = if (relative.ups > 0) Seq(s"up-${relative.ups}") else Seq()
        val segs =
          Seq("foreign-modules") ++
            ups ++
            relative.segments.init ++
            Seq(relative.segments.last.stripSuffix(".sc"))

        val segsList = segs.map(pprint.Util.literalize(_)).mkString(", ")
        s"_root_.mill.main.RootModule.Foreign(Some(_root_.mill.define.Segments.labels($segsList)))"
      }

    val miscInfoName = s"MiscInfo_$name"

    def pathToString(p: os.Path) = {
      if (p.startsWith(os.pwd)) s"_root_.os.pwd / _root_.os.RelPath(${literalize(p.subRelativeTo(os.pwd).toString())})"
      else s"_root_.os.Path(${literalize(p.toString())})"
    }

    s"""package ${pkg.map(backtickWrap).mkString(".")}
       |
       |import _root_.mill.runner.MillBuildRootModule
       |
       |object ${backtickWrap(miscInfoName)} {
       |  implicit lazy val millBuildRootModuleInfo: _root_.mill.runner.MillBuildRootModule.Info = _root_.mill.runner.MillBuildRootModule.Info(
       |    _root_.scala.Seq(
       |      ${enclosingClasspath.map(pathToString(_)).mkString(",\n      ")}
       |    ),
       |    ${pathToString(base)},
       |    ${pathToString(millTopLevelProjectRoot)},
       |    _root_.scala.Seq(${cliImports.map(literalize(_)).mkString(", ")})
       |  )
       |  implicit lazy val millBaseModuleInfo: _root_.mill.main.RootModule.Info = _root_.mill.main.RootModule.Info(
       |    millBuildRootModuleInfo.projectRoot,
       |    _root_.mill.define.Discover[${backtickWrap(name)}]
       |  )
       |}
       |import ${backtickWrap(miscInfoName)}.{millBuildRootModuleInfo, millBaseModuleInfo}
       |object ${backtickWrap(name)} extends ${backtickWrap(name)}
       |class ${backtickWrap(name)} extends $superClass {
       |
       |//MILL_ORIGINAL_FILE_PATH=${originalFilePath.relativeTo(os.pwd)}
       |//MILL_USER_CODE_START_MARKER
       |""".stripMargin
  }

  val bottom = "\n}"
}
