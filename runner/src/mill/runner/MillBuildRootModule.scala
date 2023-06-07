package mill.runner

import coursier.Repository
import mill._
import mill.api.{Loose, PathRef, Result, internal}
import mill.define.{Caller, Discover, Target, Task}
import mill.scalalib.{BoundDep, Dep, DepSyntax, Lib, ScalaModule}
import mill.util.CoursierSupport
import mill.util.Util.millProjectModule
import mill.scalalib.api.Versions
import os.{Path, rel}
import pprint.Util.literalize
import FileImportGraph.backtickWrap
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
) extends RootModule() with ScalaModule {
  override def bspDisplayName0: String = millBuildRootModuleInfo
    .projectRoot
    .relativeTo(millBuildRootModuleInfo.topLevelProjectRoot)
    .segments
    .++(super.bspDisplayName0.split("/"))
    .mkString("/")

  override def millSourcePath = millBuildRootModuleInfo.projectRoot / os.up / "mill-build"

  override def resolveDeps(
      deps: Task[Agg[BoundDep]],
      sources: Boolean = false
  ): Task[Agg[PathRef]] =
    T.task {
      if (sources == true) super.resolveDeps(deps, true)()
      else {
        // We need to resolve the sources to make GenIdeaExtendedTests pass,
        // because those do not call `resolveDeps` explicitly for build file
        // `import $ivy`s but instead rely on the deps that are resolved as
        // part of the bootstrapping process. We thus need to make sure
        // bootstrapping the rootModule ends up putting the sources on disk
        val unused = super.resolveDeps(deps, true)()
        super.resolveDeps(deps, false)()
      }
    }

  override def scalaVersion: T[String] = "2.13.10"

  def scriptSources = T.sources {
    MillBuildRootModule
      .parseBuildFiles(millBuildRootModuleInfo)
      .seenScripts
      .keys
      .map(PathRef(_))
      .toSeq
  }

  def parseBuildFiles = T {
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

  def cliImports = T.input { millBuildRootModuleInfo.cliImports }

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
        millBuildRootModuleInfo.topLevelProjectRoot,
        millBuildRootModuleInfo.cliImports
      )
      Result.Success(Seq(PathRef(T.dest)))
    }
  }

  def scriptImportGraph: T[Map[Path, (Int, Seq[Path])]] = T {
    parseBuildFiles()
      .importGraphEdges
      .map { case (path, imports) =>
        (path, (PathRef(path).hashCode(), imports))
      }
  }

  override def allSourceFiles: T[Seq[PathRef]] = T {
    Lib.findSourceFiles(allSources(), Seq("scala", "java", "sc")).map(PathRef(_))
  }

  def enclosingClasspath = T.sources {
    millBuildRootModuleInfo.enclosingClasspath.map(p => mill.api.PathRef(p, quick = true))
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

    implicit private def millBuildRootModuleInfo = MillBuildRootModule.Info(
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

  def parseBuildFiles(millBuildRootModuleInfo: MillBuildRootModule.Info) = {
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

    s"""package ${pkg.map(backtickWrap).mkString(".")}
       |
       |import _root_.mill.runner.MillBuildRootModule
       |
       |object ${backtickWrap(miscInfoName)} {
       |  implicit val millBuildRootModuleInfo: _root_.mill.runner.MillBuildRootModule.Info = _root_.mill.runner.MillBuildRootModule.Info(
       |    ${enclosingClasspath.map(p => literalize(p.toString))}.map(_root_.os.Path(_)),
       |    _root_.os.Path(${literalize(base.toString)}),
       |    _root_.os.Path(${literalize(millTopLevelProjectRoot.toString)}),
       |    _root_.scala.Seq(${cliImports.map(literalize(_)).mkString(", ")})
       |  )
       |  implicit val millBaseModuleInfo: _root_.mill.main.RootModule.Info = _root_.mill.main.RootModule.Info(
       |    millBuildRootModuleInfo.projectRoot,
       |    _root_.mill.define.Discover[${backtickWrap(name)}]
       |  )
       |}
       |import ${backtickWrap(miscInfoName)}.{millBuildRootModuleInfo, millBaseModuleInfo}
       |object ${backtickWrap(name)} extends ${backtickWrap(name)}
       |class ${backtickWrap(name)} extends $superClass {
       |
       |//MILL_ORIGINAL_FILE_PATH=${originalFilePath}
       |//MILL_USER_CODE_START_MARKER
       |""".stripMargin
  }

  val bottom = "\n}"
}
