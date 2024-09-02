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
      val scriptPath = scriptSource.path
      val specialNames = (nestedBuildFileNames ++ rootBuildFileNames).toSet

      val isBuildScript = specialNames(scriptPath.last)
      val scriptFolderPath = scriptPath / os.up

      val packageSegments = FileImportGraph.fileImportToSegments(projectRoot, scriptPath, false)
      val dest = targetDest / packageSegments

      val childNames = scriptSources
        .flatMap { p =>
          val path = p.path
          if (path == scriptPath) None
          else if (nestedBuildFileNames.contains(path.last)) {
            Option.when(path / os.up / os.up == scriptFolderPath) {
              (path / os.up).last
            }
          } else None
        }
        .distinct

      val Seq(`globalPackagePrefix`, pkg @ _*) = packageSegments

      def pkgSelector0(pre: Option[String], s: Option[String]) =
        (pre ++ pkg ++ s).map(backtickWrap).mkString(".")
      def pkgSelector2(s: Option[String]) = s"_root_.${pkgSelector0(Some(globalPackagePrefix), s)}"
      val childAliases = childNames
        .map { c =>
          // Dummy references to sub modules. Just used as metadata for the discover and
          // resolve logic to traverse, cannot actually be evaluated and used
          val comment = "// subfolder module reference"
          val lhs = backtickWrap(c)
          val rhs = s"${pkgSelector2(Some(c))}.package_"
          s"final lazy val $lhs: $rhs.type = $rhs $comment"
        }
        .mkString("\n")

      val pkgLine = s"package ${pkgSelector0(Some(globalPackagePrefix), None)}"

      // Provide `build` as an alias to the root `build_.package_`, since from the user's
      // perspective it looks like they're writing things that live in `package build`,
      // but at compile-time we rename things, we so provide an alias to preserve the fiction
      val aliasImports =
        s"""import _root_.{build_ => $$file}
           |import build_.{package_ => build}""".stripMargin

      val wrapperHeader = if (isBuildScript){
        topBuildScript(
          scriptFolderPath.relativeTo(projectRoot).segments,
          scriptFolderPath,
          enclosingClasspath,
          millTopLevelProjectRoot,
          childAliases,
        )
      } else{
        s"""object ${backtickWrap(scriptPath.baseName)} {""".stripMargin
      }

      val markerComment =
        s"""//MILL_ORIGINAL_FILE_PATH=$scriptPath
           |//MILL_USER_CODE_START_MARKER""".stripMargin

      os.write(
        dest,
        Seq(
          pkgLine,
          aliasImports,
          wrapperHeader,
          markerComment,
          scriptCode(scriptPath),
          // define this after user code so in case of conflict these lines are what turn
          // up in the error message, so we can add a comment and control what the user sees
          childAliases,
          bottom
        ).mkString("\n"),
        createFolders = true
      )
    }
  }

  def topBuildScript(
      segs: Seq[String],
      scriptFolderPath: os.Path,
      enclosingClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      childAliases: String,
  ): String = {
    val segsList = segs.map(pprint.Util.literalize(_)).mkString(", ")
    val extendsClause = if (segs.isEmpty) {
      if (millTopLevelProjectRoot == scriptFolderPath) {
        s"extends _root_.mill.main.RootModule() "
      } else {
        s"extends _root_.mill.runner.MillBuildRootModule() "
      }
    } else {
      s"extends _root_.mill.main.RootModule.Subfolder($segsList) "
    }

    val prelude =
      s"""import _root_.mill.runner.MillBuildRootModule
         |@_root_.scala.annotation.nowarn
         |object MillMiscInfo extends MillBuildRootModule.MillMiscInfo(
         |  ${enclosingClasspath.map(p => literalize(p.toString))},
         |  ${literalize(scriptFolderPath.toString)},
         |  ${literalize(millTopLevelProjectRoot.toString)},
         |  _root_.mill.define.Discover[$wrapperObjectName]
         |){
         |  // aliases so child modules can be referred to directly as `foo` rather
         |  // than `foo.module`. Need to be outside `MillPackageClass` in case they are
         |  // referenced in the combined `extends` clause
         |  $childAliases
         |}
         |import MillMiscInfo._
         |""".stripMargin

    val header =
      // User code needs to be put in a separate class for proper submodule
      // object initialization due to https://github.com/scala/scala3/issues/21444
      s"""object $wrapperObjectName extends $wrapperObjectName
         |class $wrapperObjectName $extendsClause {""".stripMargin

    s"""$prelude
       |$header
       |""".stripMargin
  }

  val bottom = "\n}"
}
