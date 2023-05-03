package mill.runner

import coursier.Repository
import mill._
import mill.api.{Loose, PathRef, Result, internal}
import mill.define.{Caller, Discover, Target, Task}
import mill.modules.CoursierSupport
import mill.modules.Util.millProjectModule
import mill.scalalib.{BoundDep, DepSyntax, Lib, ScalaModule}
import mill.scalalib.api.Versions
import os.{Path, rel}
import pprint.Util.literalize

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

  override def millSourcePath = millBuildRootModuleInfo.projectRoot / os.up / "mill-build"

  override def resolveDeps(
      deps: Task[Agg[BoundDep]],
      sources: Boolean = false
  ): Task[Agg[PathRef]] =
    T.task {
      // We need to resolve the sources to make GenIdeaExtendedTests pass for
      // some reason, but we don't need to actually return them (???)
      val unused = super.resolveDeps(deps, true)()

      super.resolveDeps(deps, false)()
    }

  override def scalaVersion = "2.13.10"

  def allBuildFiles = T.sources {
    MillBuildRootModule
      .parseBuildFiles(millBuildRootModuleInfo)
      .seenScripts
      .keys
      .map(PathRef(_))
      .toSeq
  }

  def parseBuildFiles = T {
    allBuildFiles()
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

  override def ivyDeps = T {
    Agg.from(
      MillIvy.processMillIvyDepSignature(parseBuildFiles().ivyDeps)
        .map(str =>
          mill.scalalib.Dep.parse(
            str
              .replace("$MILL_VERSION", mill.BuildInfo.millVersion)
              .replace("${MILL_VERSION}", mill.BuildInfo.millVersion)
              .replace("$MILL_BIN_PLATFORM", mill.BuildInfo.millBinPlatform)
              .replace("${MILL_BIN_PLATFORM}", mill.BuildInfo.millBinPlatform)
          )
        )
    ) ++
      Seq(ivy"com.lihaoyi::mill-moduledefs:${Versions.millModuledefsVersion}")
  }

  def scriptSources: T[Seq[PathRef]] = T.sources {
    for ((p, s) <- parseBuildFiles().seenScripts.toSeq) yield PathRef(p)
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
        millBuildRootModuleInfo.topLevelProjectRoot
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

  override def scalacPluginIvyDeps = Agg(
    ivy"com.lihaoyi:::scalac-mill-moduledefs-plugin:${Versions.millModuledefsVersion}"
  )

  override def scalacOptions: T[Seq[String]] = T {
    super.scalacOptions() ++
      Seq(
        "-Xplugin:" + lineNumberPluginClasspath().map(_.path).mkString(","),
        "-nowarn",
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
}

object MillBuildRootModule {

  class BootstrapModule(
      topLevelProjectRoot0: os.Path,
      projectRoot: os.Path,
      enclosingClasspath: Seq[os.Path]
  )(implicit baseModuleInfo: RootModule.Info) extends RootModule {

    implicit private def millBuildRootModuleInfo = MillBuildRootModule.Info(
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
      millTopLevelProjectRoot: os.Path
  ) = {
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
        scriptSource.path
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
      originalFilePath: os.Path
  ) = {

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

    s"""
       |package ${pkg.mkString(".")}
       |import _root_.mill._
       |import mill.runner.MillBuildRootModule
       |object `MiscInfo_${name}`{
       |  implicit val millBuildRootModuleInfo: _root_.mill.runner.MillBuildRootModule.Info = _root_.mill.runner.MillBuildRootModule.Info(
       |    ${enclosingClasspath.map(p => literalize(p.toString))}.map(_root_.os.Path(_)),
       |    _root_.os.Path(${literalize(base.toString)}),
       |    _root_.os.Path(${literalize(millTopLevelProjectRoot.toString)})
       |  )
       |  implicit val millBaseModuleInfo: _root_.mill.main.RootModule.Info = _root_.mill.main.RootModule.Info(
       |    millBuildRootModuleInfo.projectRoot,
       |    _root_.mill.define.Discover[$name]
       |  )
       |}
       |import `MiscInfo_${name}`.{millBuildRootModuleInfo, millBaseModuleInfo}
       |object $name extends $name
       |class $name extends $superClass{
       |
       |//MILL_ORIGINAL_FILE_PATH=${originalFilePath}
       |//MILL_USER_CODE_START_MARKER
       |""".stripMargin
  }

  val bottom = "\n}"

}
