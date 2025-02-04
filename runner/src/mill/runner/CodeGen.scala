package mill.runner

import mill.main.client.CodeGenConstants._
import mill.api.{PathRef, Result}
import mill.runner.FileImportGraph.backtickWrap
import pprint.Util.literalize

import mill.runner.worker.api.MillScalaParser
import scala.util.control.Breaks._

object CodeGen {

  def generateWrappedSources(
      projectRoot: os.Path,
      scriptSources: Seq[PathRef],
      allScriptCode: Map[os.Path, String],
      targetDest: os.Path,
      enclosingClasspath: Seq[os.Path],
      compilerWorkerClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      output: os.Path,
      parser: MillScalaParser
  ): Unit = {
    for (scriptSource <- scriptSources) breakable {
      val scriptPath = scriptSource.path
      val specialNames = (nestedBuildFileNames ++ rootBuildFileNames).toSet

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

      val pkg = packageSegments.drop(1).dropRight(1)

      def pkgSelector0(pre: Option[String], s: Option[String]) =
        (pre ++ pkg ++ s).map(backtickWrap).mkString(".")
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

      val pkgLine = s"package ${pkgSelector0(Some(globalPackagePrefix), None)}"

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

      val parts =
        if (!isBuildScript) {
          s"""$pkgLine
             |$aliasImports
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
            pkgLine,
            aliasImports,
            scriptCode,
            markerComment,
            parser
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
      pkgLine: String,
      aliasImports: String,
      scriptCode: String,
      markerComment: String,
      parser: MillScalaParser
  ) = {
    val segments = scriptFolderPath.relativeTo(projectRoot).segments

    val prelude = {
      s"""import MillMiscInfo._
         |import _root_.mill.main.TokenReaders.given, _root_.mill.api.JsonFormatters.given
         |""".stripMargin
    }

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
      throw Result.Failure(s"object `package` in $scriptPath must extend `$expectedParent`")
    }
    val misnamed =
      objectData.filter(o => o.name.text != "`package`" && o.parent.text == expectedParent)
    if (misnamed.nonEmpty) {
      throw Result.Failure(
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

        s"""$pkgLine
           |$miscInfo
           |$aliasImports
           |$prelude
           |$markerComment
           |$newScriptCode
           |object $wrapperObjectName extends $wrapperObjectName {
           |  ${childAliases.linesWithSeparators.mkString("  ")}
           |  ${millDiscover(segments.nonEmpty)}
           |}""".stripMargin

      case None =>
        s"""$pkgLine
           |$miscInfo
           |$aliasImports
           |$prelude
           |${topBuildHeader(segments, scriptFolderPath, millTopLevelProjectRoot, childAliases)}
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
    if (segmentsNonEmpty) "override def millDiscover: _root_.mill.define.Discover  = build_.package_.implicitMillDiscover"
    else """override def millDiscover: _root_.mill.define.Discover = _root_.mill.define.Discover[this.type]""".stripMargin

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
      childAliases: String
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
    s"""object ${wrapperObjectName} extends $wrapperObjectName {
       |  ${childAliases.linesWithSeparators.mkString("  ")}
       |  ${millDiscover(segments.nonEmpty)}
       |}
       |abstract class $wrapperObjectName $extendsClause { this: $wrapperObjectName.type =>
       |""".stripMargin

  }
}
