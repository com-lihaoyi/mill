package mill.runner

import mill.main.client.CodeGenConstants._
import mill.api.{PathRef, Result}
import mill.runner.FileImportGraph.backtickWrap
import pprint.Util.literalize

import scala.collection.mutable
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
      isScala3: Boolean,
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
            isScala3,
            childSels,
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
      isScala3: Boolean,
      childSels: Seq[String],
      parser: MillScalaParser
  ) = {
    val segments = scriptFolderPath.relativeTo(projectRoot).segments

    val prelude = {
      val scala3imports = if isScala3 then {
        // (Scala 3) package is not part of implicit scope
        s"""import _root_.mill.main.TokenReaders.given, _root_.mill.api.JsonFormatters.given"""
      } else {
        ""
      }
      if (segments.nonEmpty) subfolderBuildPrelude(scriptFolderPath, segments, scala3imports)
      else topBuildPrelude(
        scriptFolderPath,
        enclosingClasspath,
        compilerWorkerClasspath,
        millTopLevelProjectRoot,
        output,
        scala3imports
      )
    }

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
              "",
              "//MILL_SPLICED_CODE_START_MARKER",
              leading + "@_root_.scala.annotation.nowarn",
              leading + "protected def __innerMillDiscover: _root_.mill.define.Discover = _root_.mill.define.Discover[this.type]",
              "//MILL_SPLICED_CODE_END_MARKER", {
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
           |$aliasImports
           |$prelude
           |$markerComment
           |$newScriptCode
           |object $wrapperObjectName extends $wrapperObjectName {
           |  ${childAliases.linesWithSeparators.mkString("  ")}
           |  ${millDiscover(childSels, spliced = objectData.finalStat.nonEmpty)}
           |}""".stripMargin
      case None =>
        s"""$pkgLine
           |$aliasImports
           |$prelude
           |${topBuildHeader(
            segments,
            scriptFolderPath,
            millTopLevelProjectRoot,
            childAliases,
            childSels
          )}
           |$markerComment
           |$scriptCode
           |}""".stripMargin

    }
  }

  def subfolderBuildPrelude(
      scriptFolderPath: os.Path,
      segments: Seq[String],
      scala3imports: String
  ): String = {
    s"""object MillMiscSubFolderInfo
       |extends mill.main.SubfolderModule.Info(
       |  os.Path(${literalize(scriptFolderPath.toString)}),
       |  _root_.scala.Seq(${segments.map(pprint.Util.literalize(_)).mkString(", ")})
       |)
       |import MillMiscSubFolderInfo._
       |$scala3imports
       |""".stripMargin
  }

  def millDiscover(childSels: Seq[String], spliced: Boolean = false): String = {
    def addChildren(initial: String) =
      if childSels.nonEmpty then
        s"""{
           |      val childDiscovers: Seq[_root_.mill.define.Discover] = Seq(
           |        ${childSels.map(child => s"$child.millDiscover").mkString(",\n      ")}
           |      )
           |      childDiscovers.foldLeft($initial.value)(_ ++ _.value)
           |    }""".stripMargin
      else
        s"""$initial.value""".stripMargin

    if spliced then
      s"""override lazy val millDiscover: _root_.mill.define.Discover = {
         |    val base = this.__innerMillDiscover
         |    val initial = ${addChildren("base")}
         |    val subbed = {
         |      initial.get(classOf[$wrapperObjectName]) match {
         |        case Some(inner) => initial.updated(classOf[$wrapperObjectName.type], inner)
         |        case None => initial
         |      }
         |    }
         |    if subbed ne base.value then
         |      _root_.mill.define.Discover.apply2(value = subbed)
         |    else
         |      base
         |  }""".stripMargin
    else
      """override lazy val millDiscover: _root_.mill.define.Discover = _root_.mill.define.Discover[this.type]""".stripMargin
  }

  def topBuildPrelude(
      scriptFolderPath: os.Path,
      enclosingClasspath: Seq[os.Path],
      compilerWorkerClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      output: os.Path,
      scala3imports: String
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
       |import MillMiscInfo._
       |$scala3imports
       |""".stripMargin
  }

  def topBuildHeader(
      segments: Seq[String],
      scriptFolderPath: os.Path,
      millTopLevelProjectRoot: os.Path,
      childAliases: String,
      childSels: Seq[String]
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
       |  ${millDiscover(childSels, spliced = true)}
       |}
       |abstract class $wrapperObjectName $extendsClause {
       |protected def __innerMillDiscover: _root_.mill.define.Discover = _root_.mill.define.Discover[this.type]""".stripMargin

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
