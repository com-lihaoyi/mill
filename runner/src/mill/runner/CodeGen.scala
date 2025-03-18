package mill.runner

import scala.jdk.CollectionConverters.CollectionHasAsScala

import mill.constants.CodeGenConstants.*
import mill.api.Result
import mill.runner.FileImportGraph.backtickWrap
import pprint.Util.literalize
import mill.runner.worker.api.MillScalaParser
import scala.util.control.Breaks.*

object CodeGen {

  def generateWrappedSources(
      projectRoot: os.Path,
      allScriptCode: Map[os.Path, String],
      targetDest: os.Path,
      enclosingClasspath: Seq[os.Path],
      compilerWorkerClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      output: os.Path,
      parser: MillScalaParser
  ): Unit = {
    val scriptSources = allScriptCode.keys.toSeq.sorted
    for (scriptPath <- scriptSources) breakable {
      val specialNames = (nestedBuildFileNames.asScala ++ rootBuildFileNames.asScala).toSet

      val isBuildScript = specialNames(scriptPath.last)
      val scriptFolderPath = scriptPath / os.up

      if (scriptFolderPath == projectRoot && scriptPath.last.split('.').head == "package") {
        break()
      }

      if (scriptFolderPath != projectRoot && scriptPath.last.split('.').head == "build") {
        break()
      }

      val packageSegments = FileImportGraph.fileImportToSegments(projectRoot, scriptPath)
      val dest = targetDest / packageSegments

      val childNames = scriptSources
        .flatMap { path =>
          if (path == scriptPath) None
          else if (nestedBuildFileNames.contains(path.last)) {
            Option.when(path / os.up / os.up == scriptFolderPath) {
              (path / os.up).last
            }
          } else None
        }
        .distinct

      val pkgSegments = packageSegments.drop(1).dropRight(1)

      def pkgSelector0(pre: Option[String], s: Option[String]) =
        (pre ++ pkgSegments ++ s).map(backtickWrap).mkString(".")
      def pkgSelector2(s: Option[String]) = s"_root_.${pkgSelector0(Some(globalPackagePrefix), s)}"
      val (childSels, childAliases0) = childNames
        .map { c =>
          // Dummy references to sub-modules. Just used as metadata for the discover and
          // resolve logic to traverse, cannot actually be evaluated and used
          val comment = "// subfolder module reference"
          val lhs = backtickWrap(c + "_alias")
          val rhs = pkgSelector2(Some(c))
          (rhs, s"final lazy val $lhs: $rhs.type = $rhs $comment")
        }.unzip
      val childAliases = childAliases0.mkString("\n")

      val pkg = pkgSelector0(Some(globalPackagePrefix), None)

      val scriptCode = allScriptCode(scriptPath)

      val markerComment =
        s"""//SOURCECODE_ORIGINAL_FILE_PATH=$scriptPath
           |//SOURCECODE_ORIGINAL_CODE_START_MARKER""".stripMargin


      val parts =
        if (!isBuildScript) {
          s"""package $pkg
             |$markerComment
             |$scriptCode
             |""".stripMargin
        } else {
          generateBuildScript(
            projectRoot,
            enclosingClasspath,
            compilerWorkerClasspath,
            millTopLevelProjectRoot,
            output,
            scriptPath,
            scriptFolderPath,
            childAliases,
            pkg,
            scriptCode,
            markerComment,
            parser,
          )
        }

      os.write.over(dest, parts, createFolders = true)
    }
  }

  private def generateBuildScript(
      projectRoot: os.Path,
      enclosingClasspath: Seq[os.Path],
      compilerWorkerClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      output: os.Path,
      scriptPath: os.Path,
      scriptFolderPath: os.Path,
      childAliases: String,
      pkg: String,
      scriptCode: String,
      markerComment: String,
      parser: MillScalaParser,
  ) = {
    val segments = scriptFolderPath.relativeTo(projectRoot).segments

    val prelude =
      s"""import MillMiscInfo._
         |import _root_.mill.main.TokenReaders.given, _root_.mill.api.JsonFormatters.given
         |import language.experimental.packageObjectValues
         |""".stripMargin

    val miscInfo =
      if (segments.nonEmpty) subfolderMiscInfo(scriptFolderPath, segments)
      else rootMiscInfo(
        scriptFolderPath,
        enclosingClasspath,
        compilerWorkerClasspath,
        millTopLevelProjectRoot,
        output
      )

    val objectData = parser.parseObjectData(scriptCode)

    val isMetaBuild = projectRoot != millTopLevelProjectRoot
    val expectedParent = if (isMetaBuild) "MillBuildRootModule" else "RootModule"


    val parentClause0 =
      if (segments.nonEmpty) "main.SubfolderModule(build.millDiscover)"
      else if (!isMetaBuild) "main.RootModule"
      else "runner.MillBuildRootModule()"

    val parentClause = s"_root_.mill.$parentClause0 with MillDiscoverWrapper"

    if (objectData.exists(o => o.name.text == "`package`" && o.parent.text != expectedParent)) {
      throw new Result.Exception(s"object `package` in $scriptPath must extend `$expectedParent`")
    }
    val misnamed =
      objectData.filter(o => o.name.text != "`package`" && o.parent.text == expectedParent)
    if (misnamed.nonEmpty) {
      throw new Result.Exception(
        s"Only one RootModule named `package` can be defined in a build, not: ${misnamed.map(_.name.text).mkString(", ")}"
      )
    }

    val pkgLine = s"package $pkg"

    objectData.find(o =>
      o.name.text == "`package`" && (o.parent.text == "RootModule" || o.parent.text == "MillBuildRootModule")
    ) match {
      case Some(objectData) =>
        var newScriptCode = scriptCode

        newScriptCode = objectData.parent.applyTo(newScriptCode, parentClause)
        newScriptCode = objectData.obj.applyTo(newScriptCode, "@scala.annotation.experimental object")

        s"""$pkgLine
           |$miscInfo
           |$prelude
           |$markerComment
           |
           |$newScriptCode
           |
           |
           |trait MillDiscoverWrapper {
           |  ${millDiscover(segments.nonEmpty)}
           |  $childAliases
           |}
           |""".stripMargin

      case None =>
        s"""$pkgLine
           |$miscInfo
           |$prelude
           |object `package` extends $parentClause {
           |$markerComment
           |$scriptCode
           |}
           |
           |trait MillDiscoverWrapper {
           |  ${millDiscover(segments.nonEmpty)}
           |  $childAliases
           |}
           |""".stripMargin

    }
  }

  def subfolderMiscInfo(
      scriptFolderPath: os.Path,
      segments: Seq[String]
  ): String = {
    s"""object MillMiscInfo
       |extends mill.main.SubfolderModule.Info(
       |  os.Path(${literalize(scriptFolderPath.toString)}),
       |  _root_.scala.Seq(${segments.map(pprint.Util.literalize(_)).mkString(", ")})
       |)
       |""".stripMargin
  }

  def millDiscover(segmentsNonEmpty: Boolean): String = {
    if (segmentsNonEmpty) ""
    else {
      val rhs = "_root_.mill.define.Discover[`package`.type]"
      s"lazy val millDiscover: _root_.mill.define.Discover = $rhs"
    }
  }

  def rootMiscInfo(
      scriptFolderPath: os.Path,
      enclosingClasspath: Seq[os.Path],
      compilerWorkerClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      output: os.Path
  ): String = {
    s"""import _root_.mill.runner.MillBuildRootModule
       |@_root_.scala.annotation.nowarn
       |object MillMiscInfo extends mill.main.RootModule.Info(
       |  ${enclosingClasspath.map(p => literalize(p.toString))},
       |  ${compilerWorkerClasspath.map(p => literalize(p.toString))},
       |  ${literalize(scriptFolderPath.toString)},
       |  ${literalize(output.toString)},
       |  ${literalize(millTopLevelProjectRoot.toString)}
       |)
       |""".stripMargin
  }
}
