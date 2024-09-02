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
      millTopLevelProjectRoot: os.Path
  ): Unit = {
    for (scriptSource <- scriptSources) {
      //pprint.log(scriptSource)
      val scriptPath = scriptSource.path
      val specialNames = (nestedBuildFileNames ++ rootBuildFileNames).toSet

      val isBuildScript = specialNames(scriptPath.last)
      val scriptFolderPath = scriptPath / os.up

      if (scriptFolderPath == projectRoot && scriptPath.baseName == "package") {
        throw Result.Failure(s"Mill ${scriptPath.last} files can only be in subfolders")
      }

      if (scriptFolderPath != projectRoot && scriptPath.baseName == "build") {
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
      case class ObjectData(
                             var start: Int = -1,
                             var name: String = null,
                             var parent: String = null,
                             var end: Int = -1
                           )

      val objectData = mutable.Buffer.empty[ObjectData]
      fastparse.parse(
        scriptCode,
        Parsers.CompilationUnit(_),
        instrument = new fastparse.internal.Instrument{
          val current = collection.mutable.ArrayDeque[(String, Int)]()
          def matches(stack: String*)(t: => Unit): Unit = {
            if (current.map(_._1) == stack) { t }
          }
          def beforeParse(parser: String, index: Int): Unit = {
            current.append((parser, index))

            matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef") {
              objectData.append(ObjectData())
            }
          }
          def afterParse(parser: String, index: Int, success: Boolean): Unit = {
            if (success) {
              matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef", "`object`") {
                objectData.last.start = current.last._2
              }
              matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef", "Id") {
                objectData.last.name = scriptCode.slice(current.last._2, index)
              }
              matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef", "DefTmpl", "AnonTmpl", "NamedTmpl", "Constrs", "Constr", "AnnotType", "SimpleType", "BasicType", "TypeId", "StableId", "IdPath", "Id") {
                if (objectData.last.end == -1) {
                  objectData.last.end = index
                  objectData.last.parent = scriptCode.slice(current.last._2, index)
                }
              }
            }else{
              matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef") {
                objectData.remove(objectData.length - 1)
              }
            }

            current.removeLast()
          }
        }
      )
      //pprint.log(objectData)
      val expectedParent = if (projectRoot != millTopLevelProjectRoot) "MillBuildRootModule" else "RootModule"
      if (objectData.exists(o => o.name == "`package`" && o.parent != expectedParent)){
        throw Result.Failure(s"object `package` in $scriptPath must extend `$expectedParent`")
      }
      val misnamed = objectData.filter(o => o.name != "`package`" && o.parent == expectedParent)
      if (misnamed.nonEmpty){
        throw Result.Failure(s"Only one RootModule named `package` can be defined in a build, not: ${misnamed.map(_.name).mkString(", ")}")
      }

      val packageObjectData = objectData.find(o => o.name == "`package`" && (o.parent == "RootModule" || o.parent == "MillBuildRootModule"))
      val segments = scriptFolderPath.relativeTo(projectRoot).segments
      val newScriptCode = packageObjectData match{
        case None => scriptCode
        case Some(objectData) =>
          val substitute = // Use whitespace to try and make sure stuff to the right has the same column offset
            if (segments.isEmpty) s"class package_ extends $expectedParent"
            else {
              val segmentsStr = segments.map(pprint.Util.literalize(_)).mkString(", ")

              s"class package_ extends RootModule.Subfolder($segmentsStr)"
            }
          scriptCode.take(objectData.start) +
          substitute +
          scriptCode.drop(objectData.end)
      }

      val prelude = if (isBuildScript){
        topBuildPrelude(
          scriptFolderPath,
          enclosingClasspath,
          millTopLevelProjectRoot,
          childAliases
        )
      }else ""

      val wrapperHeader = if (isBuildScript) {
        topBuildHeader(
          segments,
          scriptFolderPath,
          millTopLevelProjectRoot,
        )
      } else {
        s"""object ${backtickWrap(scriptPath.baseName)} {""".stripMargin
      }

      val markerComment =
        s"""//MILL_ORIGINAL_FILE_PATH=$scriptPath
           |//MILL_USER_CODE_START_MARKER""".stripMargin

      val packageObject =
        if (packageObjectData.isEmpty) ""
        else {
          s"""object package_ extends package_ {
             |  $childAliases
             |}""".stripMargin
        }
      os.write(
        dest,
        Seq(
          pkgLine,
          aliasImports,
          prelude,
          if (packageObjectData.nonEmpty) "" else wrapperHeader,
          markerComment,
          newScriptCode,
          // define this after user code so in case of conflict these lines are what turn
          // up in the error message, so we can add a comment and control what the user sees
          if (packageObjectData.nonEmpty) "" else childAliases,
          if (packageObjectData.nonEmpty) "" else bottom,
          packageObject
        ).mkString("\n"),
        createFolders = true
      )
    }
  }

  def topBuildPrelude(
      scriptFolderPath: os.Path,
      enclosingClasspath: Seq[os.Path],
      millTopLevelProjectRoot: os.Path,
      childAliases: String
  ): String = {
    s"""import _root_.mill.runner.MillBuildRootModule
       |@_root_.scala.annotation.nowarn
       |object MillMiscInfo extends MillBuildRootModule.MillMiscInfo(
       |  ${enclosingClasspath.map(p => literalize(p.toString))},
       |  ${literalize(scriptFolderPath.toString)},
       |  ${literalize(millTopLevelProjectRoot.toString)},
       |  _root_.mill.define.Discover[$wrapperObjectName.type]
       |){
       |  // aliases so child modules can be referred to directly as `foo` rather
       |  // than `foo.module`. Need to be outside `MillPackageClass` in case they are
       |  // referenced in the combined `extends` clause
       |  $childAliases
       |}
       |import MillMiscInfo._
       |""".stripMargin
  }

  def topBuildHeader(
      segs: Seq[String],
      scriptFolderPath: os.Path,
      millTopLevelProjectRoot: os.Path,
  ): String = {
    val extendsClause = if (segs.isEmpty) {
      if (millTopLevelProjectRoot == scriptFolderPath) {
        s"extends _root_.mill.main.RootModule() "
      } else {
        s"extends _root_.mill.runner.MillBuildRootModule() "
      }
    } else {
      val segsList = segs.map(pprint.Util.literalize(_)).mkString(", ")
      s"extends _root_.mill.main.RootModule.Subfolder($segsList) "
    }

    // User code needs to be put in a separate class for proper submodule
    // object initialization due to https://github.com/scala/scala3/issues/21444
    s"""object $wrapperObjectName extends $wrapperObjectName
       |class $wrapperObjectName $extendsClause {""".stripMargin

  }

  val bottom = "\n}"
}
