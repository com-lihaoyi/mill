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
          val lhs = backtickWrap(c)
          val rhs = s"${pkgSelector2(Some(c))}.package_"
          (rhs, s"final lazy val $lhs: $rhs.type = $rhs $comment")
        }.unzip
      val childAliases = childAliases0.mkString("\n")

      val pkg = pkgSelector0(Some(globalPackagePrefix), None)

      val aliasImports = Seq(
        // `$file` as an alias for `build_` to make usage of `import $file` when importing
        // helper methods work
        "import _root_.{build_ => $file}",
        // Provide `build` as an alias to the root `build_.package_`, since from the user's
        // perspective it looks like they're writing things that live in `package build`,
        // but at compile-time we rename things, we so provide an alias to preserve the fiction
        "import build_.{package_ => build}"
      ).mkString("\n")

      val scriptCode = allScriptCode(scriptPath)

      val markerComment =
        s"""//SOURCECODE_ORIGINAL_FILE_PATH=$scriptPath
           |//SOURCECODE_ORIGINAL_CODE_START_MARKER""".stripMargin

      val siblingScripts = scriptSources
        .filter(_ != scriptPath)
        .filter(p => (p / os.up) == (scriptPath / os.up))
        .map(_.last.split('.').head)

      val importSiblingScripts = siblingScripts
        .filter(s => s != "build" && s != "package")
        .map(s => s"import $pkg.${backtickWrap(s)}.*").mkString("\n")

      val parts =
        if (!isBuildScript) {
          s"""package $pkg
             |$aliasImports
             |$importSiblingScripts
             |object ${backtickWrap(scriptPath.last.split('.').head)} {
             |$markerComment
             |$scriptCode
             |}""".stripMargin
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
            aliasImports,
            scriptCode,
            markerComment,
            parser,
            siblingScripts,
            importSiblingScripts
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
      aliasImports: String,
      scriptCode: String,
      markerComment: String,
      parser: MillScalaParser,
      siblingScripts: Seq[String],
      importSiblingScripts: String
  ) = {
    val segments = scriptFolderPath.relativeTo(projectRoot).segments

    val exportSiblingScripts =
      siblingScripts.map(s => s"export $pkg.${backtickWrap(s)}.*").mkString("\n")

    val prelude =
      s"""import MillMiscInfo._
         |import _root_.mill.main.TokenReaders.given, _root_.mill.api.JsonFormatters.given
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

    val expectedParent =
      if (projectRoot != millTopLevelProjectRoot) "MillBuildRootModule" else "RootModule"

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
    objectData.find(o =>
      o.name.text == "`package`" && (o.parent.text == "RootModule" || o.parent.text == "MillBuildRootModule")
    ) match {
      case Some(objectData) =>
        val newParent = if (segments.isEmpty) expectedParent else s"mill.main.SubfolderModule"

        var newScriptCode = scriptCode
        objectData.endMarker match {
          case Some(endMarker) =>
            newScriptCode = endMarker.applyTo(newScriptCode, wrapperObjectName)
          case None =>
            ()
        }
        objectData.finalStat match {
          case Some((leading, finalStat)) =>
            val fenced = Seq(
              "", {
                val statLines = finalStat.text.linesWithSeparators.toSeq
                if statLines.sizeIs > 1 then
                  statLines.tail.mkString
                else
                  finalStat.text
              }
            ).mkString(System.lineSeparator())
            newScriptCode = finalStat.applyTo(newScriptCode, fenced)
          case None =>
            ()
        }

        newScriptCode = objectData.parent.applyTo(newScriptCode, newParent)
        newScriptCode = objectData.name.applyTo(newScriptCode, wrapperObjectName)
        newScriptCode = objectData.obj.applyTo(newScriptCode, "abstract class")

        s"""package $pkg
           |$miscInfo
           |$aliasImports
           |$importSiblingScripts
           |$prelude
           |$markerComment
           |$newScriptCode
           |object $wrapperObjectName extends $wrapperObjectName {
           |  ${childAliases.linesWithSeparators.mkString("  ")}
           |  $exportSiblingScripts
           |  ${millDiscover(segments.nonEmpty)}
           |}""".stripMargin

      case None =>
        s"""package $pkg
           |$miscInfo
           |$aliasImports
           |$importSiblingScripts
           |$prelude
           |${topBuildHeader(
            segments,
            scriptFolderPath,
            millTopLevelProjectRoot,
            childAliases,
            exportSiblingScripts
          )}
           |$markerComment
           |$scriptCode
           |}""".stripMargin

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
    val rhs =
      if (segmentsNonEmpty) "build_.package_.millDiscover"
      else "_root_.mill.define.Discover[this.type]"

    s"override lazy val millDiscover: _root_.mill.define.Discover = $rhs"
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

  def topBuildHeader(
      segments: Seq[String],
      scriptFolderPath: os.Path,
      millTopLevelProjectRoot: os.Path,
      childAliases: String,
      exportSiblingScripts: String
  ): String = {
    val extendsClause =
      if (segments.nonEmpty) s"extends _root_.mill.main.SubfolderModule "
      else if (millTopLevelProjectRoot == scriptFolderPath)
        s"extends _root_.mill.main.RootModule() "
      else s"extends _root_.mill.runner.MillBuildRootModule() "

    // User code needs to be put in a separate class for proper submodule
    // object initialization due to https://github.com/scala/scala3/issues/21444
    // TODO: Scala 3 - the discover needs to be moved to the object, however,
    // path dependent types no longer match, e.g. for TokenReaders of custom types.
    // perhaps we can patch mainargs to substitute prefixes when summoning TokenReaders?
    // or, add an optional parameter to Discover.apply to substitute the outer class?
    s"""object ${wrapperObjectName} extends $wrapperObjectName  {
       |  ${childAliases.linesWithSeparators.mkString("  ")}
       |  $exportSiblingScripts
       |  ${millDiscover(segments.nonEmpty)}
       |}
       |abstract class $wrapperObjectName $extendsClause { this: $wrapperObjectName.type =>
       |""".stripMargin

  }
}
