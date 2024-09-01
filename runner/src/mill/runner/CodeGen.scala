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

      val dest =
        targetDest / FileImportGraph.fileImportToSegments(projectRoot, scriptPath, false)

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

      val sibling = scriptSources
        .collect {
          case p if p.path != scriptPath && p.path / os.up == scriptFolderPath => p.path.baseName
        }
        .distinct

      val Seq(`globalPackagePrefix`, pkg @ _*) =
        FileImportGraph.fileImportToSegments(projectRoot, scriptFolderPath, true)

      def pkgSelector0(pre: Option[String], s: Option[String]) =
        ((pre ++ pkg ++ s).map(backtickWrap)).mkString(".")
      val pkgSelector = pkgSelector0(None, None)
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

      val newSource = topBuild(
        scriptPath.baseName,
        scriptFolderPath.relativeTo(projectRoot).segments,
        scriptFolderPath,
        enclosingClasspath,
        millTopLevelProjectRoot,
        childAliases,
        isBuildScript,
        sibling.map(t =>
          s"import ${pkgSelector2(t match {
              case "package" | "build" => None
              case _ => Some(t)
            })}._"
        ).mkString("\n")
      )

      val pkgLine =
        s"package $globalPackagePrefix; " + (if (pkg.nonEmpty) s"package $pkgSelector" else "")

      val markerComment =
        s"""//MILL_ORIGINAL_FILE_PATH=$scriptPath
           |//MILL_USER_CODE_START_MARKER""".stripMargin

      os.write(
        dest,
        Seq(
          pkgLine,
          newSource,
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

  def topBuild(
      name: String,
      segs: Seq[String],
      scriptFolderPath: os.Path,
      enclosingClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      childAliases: String,
      isBuildScript: Boolean,
      adjacentImports: String
  ): String = {
    val segsList = segs.map(pprint.Util.literalize(_)).mkString(", ")
    val extendsClause =
      if (!isBuildScript) "" // Non-`build.sc`/`package.sc` files cannot define modules
      else if (segs.isEmpty) {
        if (millTopLevelProjectRoot == scriptFolderPath) {
          s"extends _root_.mill.main.RootModule($segsList) "
        } else {
          s"extends _root_.mill.runner.MillBuildRootModule($segsList) "
        }
      } else {
        s"extends _root_.mill.main.RootModule.Subfolder($segsList) "
      }

    // MillMiscInfo defines a bunch of module metadata that is only relevant
    // for `build.sc`/`package.sc` files that can define modules
    val prelude = if (isBuildScript) {
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
    } else {
      ""
    }

    // `build` refers to different things in `build.sc` and outside `build.sc`. That is
    // `build` refers to the `build.sc` object itself, and if we import it we get a circular
    // dependency compiler error. However, most times in `build.sc` when you use `build` you
    // are referring to things in other files, and so using `MillMiscInfo` which has the
    // relevant forwarders is sufficient even if it doesn't give full access to the `build.sc`
    // definitions
    val buildImport =
      if (true) s"import build_.{package_ => build}"
      else "import build_.{MillMiscInfo => build}"



    val header = if (isBuildScript) {
      s"""object $wrapperObjectName extends $wrapperObjectName
         |// User code needs to be put in a separate class for proper submodule
         |// object initialization due to https://github.com/scala/scala3/issues/21444
         |class $wrapperObjectName $extendsClause {""".stripMargin
    } else {
      s"object ${backtickWrap(name)} {"
    }
    s"""
       |$prelude
       |$buildImport
       |$adjacentImports
       |$header
       |""".stripMargin
  }

  val bottom = "\n}"
}
