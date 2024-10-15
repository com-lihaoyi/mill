package mill.runner

import mill.main.client.CodeGenConstants._
import mill.api.{PathRef, Result}
import mill.runner.FileImportGraph.backtickWrap
import pprint.Util.literalize

import scala.collection.mutable

object CodeGen {

  def generateWrappedSources(
      projectRoot: os.Path,
      scriptSources: Seq[PathRef],
      allScriptCode: Map[os.Path, String],
      targetDest: os.Path,
      enclosingClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      output: os.Path
  ): Unit = {
    for (scriptSource <- scriptSources) {
      val scriptPath = scriptSource.path
      val specialNames = (nestedBuildFileNames ++ rootBuildFileNames).toSet

      val isBuildScript = specialNames(scriptPath.last)
      val scriptFolderPath = scriptPath / os.up

      if (scriptFolderPath == projectRoot && scriptPath.last.split('.').head == "package") {
        throw Result.Failure(s"Mill ${scriptPath.last} files can only be in subfolders")
      }

      if (scriptFolderPath != projectRoot && scriptPath.last.split('.').head == "build") {
        throw Result.Failure(s"Mill ${scriptPath.last} files can only be in the project root")
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
        s"""//MILL_ORIGINAL_FILE_PATH=$scriptPath
           |//MILL_USER_CODE_START_MARKER""".stripMargin

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
            millTopLevelProjectRoot,
            output,
            scriptPath,
            scriptFolderPath,
            childAliases,
            pkgLine,
            aliasImports,
            scriptCode,
            markerComment
          )
        }

      os.write.over(dest, parts, createFolders = true)
    }
  }

  private def generateBuildScript(
      projectRoot: os.Path,
      enclosingClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      output: os.Path,
      scriptPath: os.Path,
      scriptFolderPath: os.Path,
      childAliases: String,
      pkgLine: String,
      aliasImports: String,
      scriptCode: String,
      markerComment: String
  ) = {
    val segments = scriptFolderPath.relativeTo(projectRoot).segments

    val prelude =
      if (segments.nonEmpty) subfolderBuildPrelude(scriptFolderPath, segments)
      else topBuildPrelude(scriptFolderPath, enclosingClasspath, millTopLevelProjectRoot, output)

    val instrument = new ObjectDataInstrument(scriptCode)
    fastparse.parse(scriptCode, Parsers.CompilationUnit(_), instrument = instrument)
    val objectData = instrument.objectData

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
        newScriptCode = objectData.parent.applyTo(newScriptCode, newParent)
        newScriptCode = objectData.name.applyTo(newScriptCode, wrapperObjectName)
        newScriptCode = objectData.obj.applyTo(newScriptCode, "abstract class")

        val millDiscover = discoverSnippet(segments)

        s"""$pkgLine
           |$aliasImports
           |$prelude
           |$markerComment
           |$newScriptCode
           |object $wrapperObjectName extends $wrapperObjectName {
           |  $childAliases
           |  $millDiscover
           |}""".stripMargin
      case None =>
        s"""$pkgLine
           |$aliasImports
           |$prelude
           |${topBuildHeader(segments, scriptFolderPath, millTopLevelProjectRoot, childAliases)}
           |$markerComment
           |$scriptCode
           |}""".stripMargin

    }
  }

  def subfolderBuildPrelude(scriptFolderPath: os.Path, segments: Seq[String]): String = {
    s"""object MillMiscSubFolderInfo
       |extends mill.main.SubfolderModule.Info(
       |  os.Path(${literalize(scriptFolderPath.toString)}),
       |  _root_.scala.Seq(${segments.map(pprint.Util.literalize(_)).mkString(", ")})
       |)
       |import MillMiscSubFolderInfo._
       |""".stripMargin
  }

  def topBuildPrelude(
      scriptFolderPath: os.Path,
      enclosingClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      output: os.Path
  ): String = {
    s"""import _root_.mill.runner.MillBuildRootModule
       |@_root_.scala.annotation.nowarn
       |object MillMiscInfo extends mill.main.RootModule.Info(
       |  ${enclosingClasspath.map(p => literalize(p.toString))},
       |  ${literalize(scriptFolderPath.toString)},
       |  ${literalize(output.toString)},
       |  ${literalize(millTopLevelProjectRoot.toString)}
       |)
       |import MillMiscInfo._
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

    val millDiscover = discoverSnippet(segments)

    // User code needs to be put in a separate class for proper submodule
    // object initialization due to https://github.com/scala/scala3/issues/21444
    s"""object $wrapperObjectName extends $wrapperObjectName{
       |  $childAliases
       |  $millDiscover
       |}
       |abstract class $wrapperObjectName $extendsClause {""".stripMargin

  }

  def discoverSnippet(segments: Seq[String]): String = {
    if (segments.nonEmpty) ""
    else
      """override lazy val millDiscover: _root_.mill.define.Discover = _root_.mill.define.Discover[this.type]
        |""".stripMargin

  }

  private case class Snippet(var text: String = null, var start: Int = -1, var end: Int = -1) {
    def applyTo(s: String, replacement: String): String =
      s.patch(start, replacement.padTo(end - start, ' '), end - start)
  }

  private case class ObjectData(obj: Snippet, name: Snippet, parent: Snippet)

  // Use Fastparse's Instrument API to identify top-level `object`s during a parse
  // and fish out the start/end indices and text for parts of the code that we need
  // to mangle and replace
  private class ObjectDataInstrument(scriptCode: String) extends fastparse.internal.Instrument {
    val objectData: mutable.Buffer[ObjectData] = mutable.Buffer.empty[ObjectData]
    val current: mutable.ArrayDeque[(String, Int)] = collection.mutable.ArrayDeque[(String, Int)]()
    def matches(stack: String*)(t: => Unit): Unit = if (current.map(_._1) == stack) { t }
    def beforeParse(parser: String, index: Int): Unit = {
      current.append((parser, index))
      matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef") {
        objectData.append(ObjectData(Snippet(), Snippet(), Snippet()))
      }
    }
    def afterParse(parser: String, index: Int, success: Boolean): Unit = {
      if (success) {
        def saveSnippet(s: Snippet) = {
          s.text = scriptCode.slice(current.last._2, index)
          s.start = current.last._2
          s.end = index
        }
        matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef", "`object`") {
          saveSnippet(objectData.last.obj)
        }
        matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef", "Id") {
          saveSnippet(objectData.last.name)
        }
        matches(
          "CompilationUnit",
          "StatementBlock",
          "TmplStat",
          "BlockDef",
          "ObjDef",
          "DefTmpl",
          "AnonTmpl",
          "NamedTmpl",
          "Constrs",
          "Constr",
          "AnnotType",
          "SimpleType",
          "BasicType",
          "TypeId",
          "StableId",
          "IdPath",
          "Id"
        ) {
          if (objectData.last.parent.text == null) saveSnippet(objectData.last.parent)
        }
      } else {
        matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef") {
          objectData.remove(objectData.length - 1)
        }
      }

      current.removeLast()
    }
  }

}
