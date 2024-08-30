package mill.runner

import mill.main.client.CodeGenConstants._
import mill.api.PathRef
import mill.runner.FileImportGraph.backtickWrap
import pprint.Util.literalize

object CodeGen {

  def generateWrappedSources(
      projectRoot: os.Path,
      scriptSources: Seq[PathRef],
      scriptCode: Map[os.Path, String],
      targetDest: os.Path,
      enclosingClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path
  ): Unit = {

    for (scriptSource <- scriptSources) {
      val scriptFolderPath = scriptSource.path / os.up
      val relative = scriptSource.path.relativeTo(projectRoot)
      val dest =
        targetDest / FileImportGraph.fileImportToSegments(projectRoot, scriptSource.path, false)

      val childNames = scriptSources
        .collect { case p if p.path.last == nestedBuildFileName => p.path / os.up }
        .filter(p => p.startsWith(scriptFolderPath))
        .map(_.subRelativeTo(scriptFolderPath).segments)
        .collect { case Seq(single) => single }
        .distinct

      val Seq(`globalPackagePrefix`, pkg @ _*) =
        FileImportGraph.fileImportToSegments(projectRoot, scriptFolderPath, true)

      val pkgSelector = pkg.map(backtickWrap).mkString(".")
      val childAliases = childNames
        .map { c =>
          // Dummy references to sub modules. Just used as metadata for the discover and
          // resolve logic to traverse, cannot actually be evaluated and used
          val comment = "// subfolder module reference"
          val lhs = backtickWrap(c)
          val selector = (pkg :+ backtickWrap(c)).map(backtickWrap).mkString(".")
          val rhs = s"_root_.$globalPackagePrefix.$selector.package_"
          s"final lazy val $lhs: $rhs.type = $rhs $comment"
        }
        .mkString("\n")

      val specialNames = Set(nestedBuildFileName, rootBuildFileName)
      val (newSource, newSuffix) =
        if (specialNames(scriptSource.path.last)) {

          topBuild(
            relative.segments.init,
            scriptFolderPath,
            scriptSource.path.baseName,
            enclosingClasspath,
            millTopLevelProjectRoot,
            childAliases
          ) -> childAliases
        } else {
          s"""object ${backtickWrap(scriptSource.path.baseName)} {
             |""".stripMargin -> ""
        }

      val pkgLine =
        s"package $globalPackagePrefix; " + (if (pkg.nonEmpty) s"package $pkgSelector" else "")

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

          // define this after user code so in case of conflict these lines are what turn
          // up in the error message, so we can add a comment and control what the user sees
          newSuffix,
          bottom
        ).mkString("\n"),
        createFolders = true
      )
    }
  }

  def topBuild(
      segs: Seq[String],
      scriptFolderPath: os.Path,
      name: String,
      enclosingClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      childAliases: String
  ): String = {
    val segsList = segs.map(pprint.Util.literalize(_)).mkString(", ")
    val superClass =
      if (segs.isEmpty) {
        if (millTopLevelProjectRoot == scriptFolderPath) "_root_.mill.main.RootModule"
        else "_root_.mill.runner.MillBuildRootModule"
      } else "_root_.mill.main.RootModule.Subfolder"

    s"""
       |import _root_.mill.runner.MillBuildRootModule
       |@_root_.scala.annotation.nowarn
       |object MillMiscInfo extends MillBuildRootModule.MillMiscInfo(
       |  ${enclosingClasspath.map(p => literalize(p.toString))},
       |  ${literalize(scriptFolderPath.toString)},
       |  ${literalize(millTopLevelProjectRoot.toString)},
       |  _root_.mill.define.Discover[MillPackageClass]
       |){
       |  // aliases so child modules can be referred to directly as `foo` rather
       |  // than `foo.module`. Need to be outside `MillPackageClass` in case they are
       |  // referenced in the combined `extends` clause
       |  $childAliases
       |  lazy val $rootModuleAlias = _root_.$globalPackagePrefix.$wrapperObjectName
       |}
       |import MillMiscInfo._
       |${if (segs.nonEmpty) s"import MillMiscInfo.$rootModuleAlias._" else ""}
       |object $wrapperObjectName extends MillPackageClass
       |// User code needs to be put in a separate class for proper submodule
       |// object initialization due to https://github.com/scala/scala3/issues/21444
       |class MillPackageClass
       |extends $superClass($segsList) {
       |""".stripMargin
  }

  val bottom = "\n}"

}
