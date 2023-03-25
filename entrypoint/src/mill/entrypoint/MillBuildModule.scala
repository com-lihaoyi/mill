package mill.entrypoint

import mill._
import mill.api.{PathRef, Result, internal}
import mill.define.Task
import mill.scalalib.{BoundDep, DepSyntax, Lib, Versions}
import os.Path
@internal
class MillBuildModule(enclosingClasspath: Seq[os.Path], projectRoot: os.Path)
  extends mill.define.BaseModule(projectRoot)(implicitly, implicitly, implicitly, implicitly, mill.define.Caller(()))
  with mill.scalalib.ScalaModule{

  def millSourcePath = projectRoot / "mill-build"

  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]

  def resolveDeps(deps: Task[Agg[BoundDep]], sources: Boolean = false): Task[Agg[PathRef]] = T.task{
    // We need to resolve the sources to make GenIdeaExtendedTests pass for
    // some reason, but we don't need to actually return them (???)
    val unused = super.resolveDeps(deps, true)()

    super.resolveDeps(deps, false)()
  }
  def scalaVersion = "2.13.10"

  def parseBuildFiles = T.input {
    FileImportGraph.parseBuildFiles(projectRoot)
  }

  def ivyDeps = T {
    Agg.from(
      parseBuildFiles().ivyDeps
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

  def scriptSources = T.sources {
    for ((p, s) <- parseBuildFiles().seenScripts.toSeq) yield PathRef(p)
  }

  def generatedSources = T {
    val parsed = parseBuildFiles()
    if (parsed.errors.nonEmpty) Result.Failure(parsed.errors.mkString("\n"))
    else {
      MillBuildModule.generateWrappedSources(projectRoot, scriptSources(), parsed.seenScripts, T.dest)
      Result.Success(Seq(PathRef(T.dest)))
    }
  }

  def scriptImportGraph = T {
    parseBuildFiles().importGraphEdges.map {
      case (k, vs) =>
        // Convert the import graph from source-file paths to generated-file paths
        def normalize(p: os.Path) =
          generatedSources().head.path / FileImportGraph.fileImportToSegments(projectRoot, p, false)
        (normalize(k), vs.map(normalize))
    }
  }

  override def allSourceFiles: T[Seq[PathRef]] = T {
    Lib.findSourceFiles(allSources(), Seq("scala", "java", "sc")).map(PathRef(_))
  }

  def unmanagedClasspath = mill.define.Target.input {
    mill.api.Loose.Agg.from(enclosingClasspath.map(p => mill.api.PathRef(p))) ++
    lineNumberPluginClasspath()
  }

  def scalacPluginIvyDeps = Agg(
    ivy"com.lihaoyi:::scalac-mill-moduledefs-plugin:${Versions.millModuledefsVersion}"
  )

  def scalacOptions = T{
    super.scalacOptions() ++
    Seq("-Xplugin:" + lineNumberPluginClasspath().map(_.path).mkString(","), "-nowarn")
  }

  def scalacPluginClasspath = super.scalacPluginClasspath() ++ lineNumberPluginClasspath()

  def lineNumberPluginClasspath: T[Agg[PathRef]] = T {
    mill.modules.Util.millProjectModule(
      "MILL_LINENUMBERS",
      "mill-entrypoint-linenumbers",
      repositoriesTask()
    )
  }
}

object MillBuildModule{
  def generateWrappedSources(base: os.Path,
                             scriptSources: Seq[PathRef],
                             scriptCode: Map[Path, String],
                             targetDest: os.Path) = {
    for (scriptSource <- scriptSources) {
      val relative = scriptSource.path.relativeTo(base)
      val dest = targetDest / FileImportGraph.fileImportToSegments(base, scriptSource.path, false)
      os.write(
        dest,
        MillBuildModule.top(
          relative,
          scriptSource.path / os.up,
          FileImportGraph.fileImportToSegments(base, scriptSource.path, true).dropRight(1),
          scriptSource.path.baseName
        ) +
        scriptCode(scriptSource.path) +
        MillBuildModule.bottom,
        createFolders = true
      )
    }
  }

  def top(relative: os.RelPath, base: os.Path, pkg: Seq[String], name: String) = {
    val foreign =
      if (pkg.size > 1 || name != "build") {
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
        s"Some(_root_.mill.define.Segments.labels($segsList))"
      } else "None"
    s"""
       |package ${pkg.mkString(".")}
       |import _root_.mill._
       |object $name
       |extends _root_.mill.define.BaseModule(_root_.os.Path(${pprint.Util.literalize(base.toString)}), foreign0 = $foreign)(
       |  implicitly, implicitly, implicitly, implicitly, _root_.mill.define.Caller(())
       |)
       |with $name{
       |  @_root_.scala.annotation.nowarn("cat=deprecation")
       |  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]
       |}
       |
       |sealed trait $name extends _root_.mill.main.MainModule{
       |
       |//MILL_USER_CODE_START_MARKER
       |""".stripMargin
  }

  val bottom = "\n}"


}
