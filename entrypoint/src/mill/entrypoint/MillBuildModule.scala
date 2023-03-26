package mill.entrypoint

import mill._
import mill.api.{PathRef, Result, internal}
import mill.define.{Caller, Discover, Task}
import mill.scalalib.{BoundDep, DepSyntax, Lib, Versions}
import pprint.Util.literalize


class MillBuildWrapperModule(topLevelProjectRoot0: os.Path,
                             projectRoot: os.Path,
                             enclosingClasspath: Seq[os.Path]) extends mill.define.BaseModule(projectRoot)(implicitly, implicitly, implicitly, implicitly, Caller(())) {
  implicit lazy val millDiscover: Discover[this.type] = Discover[this.type]

  object millbuild extends MillBuildModule {
    def millBuildEnclosingClasspath = enclosingClasspath
    def millBuildProjectRoot = projectRoot
    def millTopLevelProjectRoot = topLevelProjectRoot0
  }
}

@internal
trait MillBuildModule extends mill.scalalib.ScalaModule{

  def millBuildEnclosingClasspath: Seq[os.Path]
  def millBuildProjectRoot: os.Path
  def millTopLevelProjectRoot: os.Path

  def millSourcePath = millBuildProjectRoot / "mill-build"

  def resolveDeps(deps: Task[Agg[BoundDep]], sources: Boolean = false): Task[Agg[PathRef]] = T.task {
    // We need to resolve the sources to make GenIdeaExtendedTests pass for
    // some reason, but we don't need to actually return them (???)
    val unused = super.resolveDeps(deps, true)()

    super.resolveDeps(deps, false)()
  }

  def scalaVersion = "2.13.10"

  def parseBuildFiles = T.input {
    FileImportGraph.parseBuildFiles(millTopLevelProjectRoot, millBuildProjectRoot)
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
    generateScriptSources()
  }

  def generateScriptSources = T{
    val parsed = parseBuildFiles()
    if (parsed.errors.nonEmpty) Result.Failure(parsed.errors.mkString("\n"))
    else {
      MillBuildModule.generateWrappedSources(
        millBuildProjectRoot,
        scriptSources(),
        parsed.seenScripts,
        T.dest,
        millBuildEnclosingClasspath,
        millTopLevelProjectRoot
      )
      Result.Success(Seq(PathRef(T.dest)))
    }
  }

  def scriptImportGraph = T {
    parseBuildFiles().importGraphEdges.map {
      case (k, vs) =>
        // Convert the import graph from source-file paths to generated-file paths
        def normalize(p: os.Path) =
          generatedSources().head.path / FileImportGraph.fileImportToSegments(millBuildProjectRoot, p, false)

        (normalize(k), vs.map(normalize))
    }
  }

  override def allSourceFiles: T[Seq[PathRef]] = T {
    Lib.findSourceFiles(allSources(), Seq("scala", "java", "sc")).map(PathRef(_))
  }

  def unmanagedClasspath = mill.define.Target.input {
    mill.api.Loose.Agg.from(millBuildEnclosingClasspath.map(p => mill.api.PathRef(p))) ++
      lineNumberPluginClasspath()
  }

  def scalacPluginIvyDeps = Agg(
    ivy"com.lihaoyi:::scalac-mill-moduledefs-plugin:${Versions.millModuledefsVersion}"
  )

  def scalacOptions = T {
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
                             scriptCode: Map[os.Path, String],
                             targetDest: os.Path,
                             enclosingClasspath: Seq[os.Path],
                             millTopLevelProjectRoot: os.Path) = {
    for (scriptSource <- scriptSources) {
      val relative = scriptSource.path.relativeTo(base)
      val dest = targetDest / FileImportGraph.fileImportToSegments(base, scriptSource.path, false)
      val newSource = MillBuildModule.top(
        relative,
        scriptSource.path / os.up,
        FileImportGraph.fileImportToSegments(base, scriptSource.path, true).dropRight(1),
        scriptSource.path.baseName,
        enclosingClasspath,
        millTopLevelProjectRoot,
        scriptSource.path
      ) +
        scriptCode(scriptSource.path) +
        MillBuildModule.bottom

      os.write(dest, newSource , createFolders = true)
    }
  }

  def top(relative: os.RelPath,
          base: os.Path,
          pkg: Seq[String],
          name: String,
          enclosingClasspath: Seq[os.Path],
          millTopLevelProjectRoot: os.Path,
          originalFilePath: os.Path) = {
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
       |extends _root_.mill.define.BaseModule(_root_.os.Path(${literalize(base.toString)}), foreign0 = $foreign)(
       |  implicitly, implicitly, implicitly, implicitly, _root_.mill.define.Caller(())
       |)
       |with $name{
       |  @_root_.scala.annotation.nowarn("cat=deprecation")
       |  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]
       |}
       |
       |sealed trait $name extends _root_.mill.main.MainModule{
       |trait MillBuildModule extends mill.entrypoint.MillBuildModule {
       |  def millBuildEnclosingClasspath = ${enclosingClasspath.map(p => literalize(p.toString))}.map(_root_.os.Path(_))
       |  def millBuildProjectRoot = _root_.os.Path(${literalize((base / os.up).toString)})
       |  def millTopLevelProjectRoot = _root_.os.Path(${literalize(millTopLevelProjectRoot.toString)})
       |}
       |//MILL_ORIGINAL_FILE_PATH=${originalFilePath}
       |//MILL_USER_CODE_START_MARKER
       |""".stripMargin
  }

  val bottom = "\n}"


}
