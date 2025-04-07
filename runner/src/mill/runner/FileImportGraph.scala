package mill.runner

import mill.api.internal
import mill.constants.CodeGenConstants.*
import mill.constants.OutFiles.*
import mill.runner.worker.api.{ImportTree, MillScalaParser}
import scala.reflect.NameTransformer.encode
import scala.collection.mutable
import scala.jdk.CollectionConverters.CollectionHasAsScala
import mill.internal.Util.backtickWrap

/**
 * @param seenScripts
 * @param repos
 * @param ivyDeps
 * @param importGraphEdges
 * @param errors
 * @param metaBuild If `true`, a meta-build is enabled
 */
@internal
case class FileImportGraph(
    seenScripts: Map[os.Path, String],
    repos: Seq[(String, os.Path)],
    ivyDeps: Set[String],
    errors: Seq[String],
    metaBuild: Boolean,
    buildFile: String
)

/**
 * Logic around traversing the `import $file` graph, extracting necessary info
 * and converting it to a convenient data structure for downstream code to use
 */
@internal
object FileImportGraph {

  import mill.api.JsonFormatters.pathReadWrite
  implicit val readWriter: upickle.default.ReadWriter[FileImportGraph] = upickle.default.macroRW

  /**
   * We perform a depth-first traversal of the import graph of `.sc` files,
   * starting from `build.mill`, collecting the information necessary to
   * instantiate the [[MillRootModule]]
   */
  def parseBuildFiles(
      parser: MillScalaParser,
      topLevelProjectRoot: os.Path,
      projectRoot: os.Path,
      output: os.Path
  ): FileImportGraph = {
    val seenScripts = mutable.Map.empty[os.Path, String]
    val seenIvy = mutable.Set.empty[String]
    val seenRepo = mutable.ListBuffer.empty[(String, os.Path)]
    val errors = mutable.Buffer.empty[String]
    var millImport = false
    var packagesImport = false

    def processScript(s: os.Path, useDummy: Boolean = false): Unit = {
      val readFileEither = scala.util.Try {
        val content = if (useDummy) "" else os.read(s)
        val fileName = s.relativeTo(topLevelProjectRoot).toString
        for (splitted <- parser.splitScript(content, fileName))
          yield {
            val (pkgs, stmts) = splitted
            val importSegments = pkgs.mkString(".")

            val expectedImportSegments0 =
              Seq(rootModuleAlias) ++
                (s / os.up).relativeTo(projectRoot).segments

            val expectedImportSegments = expectedImportSegments0.map(backtickWrap).mkString(".")
            if (
              // Legacy `.sc` files have their package build be optional
              (s.last.endsWith(".mill") || s.last.endsWith(".mill.scala")) &&
              expectedImportSegments != importSegments &&
              // Root build.mill file has its `package build` be optional
              !(importSegments == "" && rootBuildFileNames.contains(s.last))
            ) {
              val expectedImport =
                if (expectedImportSegments.isEmpty) "<none>"
                else s"\"package $expectedImportSegments\""
              errors.append(
                s"Package declaration \"package $importSegments\" in " +
                  s"${s.relativeTo(topLevelProjectRoot)} does not match " +
                  s"folder structure. Expected: $expectedImport"
              )
            }
            stmts
          }

      } match {
        case scala.util.Failure(ex) => Left(ex.getClass.getName + " " + ex.getMessage)
        case scala.util.Success(value) => value
      }
      readFileEither match {
        case Left(err) =>
          // Make sure we mark even scripts that failed to parse as seen, so
          // they can be watched and the build can be re-triggered if the user
          // fixes the parse error
          seenScripts(s) = ""
          errors.append(err)

        case Right(stmts) =>
          // we don't expect any new imports when using an empty dummy
          val transformedStmts = mutable.Buffer.empty[String]
          for ((stmt0, importTrees) <- parser.parseImportHooksWithIndices(stmts)) {
            walkStmt(s, stmt0, importTrees, transformedStmts)
          }
          seenScripts(s) = transformedStmts.mkString
      }
    }

    def walkStmt(
        s: os.Path,
        stmt0: String,
        importTrees: Seq[ImportTree],
        transformedStmts: mutable.Buffer[String]
    ) = {

      var stmt = stmt0
      for (importTree <- importTrees) {
        val (start, patchString, end) = importTree match {
          case ImportTree(Seq(("$repo", _), rest @ _*), mapping, start, end) =>
            for {
              repo <- mapping.map(_._1)
              if seenRepo.find(_._1 == repo).isEmpty
            } seenRepo.addOne((repo, s))
            (start, "_root_._", end)

          case ImportTree(Seq(("$ivy", _), rest @ _*), mapping, start, end) =>
            seenIvy.addAll(mapping.map(_._1))
            (start, "_root_._", end)

          case ImportTree(Seq(("$meta", _), rest @ _*), mapping, start, end) =>
            millImport = true
            (start, "_root_._", end)

          case ImportTree(Seq(("$packages", _), rest @ _*), mapping, start, end) =>
            packagesImport = true
            (start, "_root_._", end)
        }

        val numNewLines = stmt.substring(start, end).count(_ == '\n')

        stmt = stmt.patch(start, patchString + System.lineSeparator() * numNewLines, end - start)
      }

      transformedStmts.append(stmt)
    }

    val (useDummy, foundRootBuildFileName) = findRootBuildFiles(projectRoot)

    processScript(projectRoot / foundRootBuildFileName, useDummy)

    walkBuildFiles(projectRoot, output).foreach(processScript(_))

    new FileImportGraph(
      seenScripts.toMap,
      seenRepo.toSeq,
      seenIvy.toSet,
      errors.toSeq,
      millImport,
      foundRootBuildFileName
    )
  }

  def findRootBuildFiles(projectRoot: os.Path) = {
    val rootBuildFiles = rootBuildFileNames.asScala
      .filter(rootBuildFileName => os.exists(projectRoot / rootBuildFileName))

    val (dummy, foundRootBuildFileName) = rootBuildFiles.toSeq match {
      case Nil => (true, rootBuildFileNames.get(0))
      case Seq(single) => (false, single)
      case multiple =>
        System.err.println(
          "Multiple root build files found: " + multiple.mkString(",") +
            ", picking " + multiple.head
        )
        (false, multiple.head)
    }

    (dummy, foundRootBuildFileName)
  }

  def walkBuildFiles(projectRoot: os.Path, output: os.Path): Seq[os.Path] = {
    if (!os.exists(projectRoot)) Nil
    else {
      val (dummy, foundRootBuildFileName) = findRootBuildFiles(projectRoot)

      val buildFileExtension =
        buildFileExtensions.asScala.find(ex => foundRootBuildFileName.endsWith(s".$ex")).get

      val nestedBuildFileName = s"package.$buildFileExtension"
      val buildFiles = os
        .walk(
          projectRoot,
          followLinks = true,
          skip = p =>
            p == output ||
              p == projectRoot / millBuild ||
              (os.isDir(p) && !os.exists(p / nestedBuildFileName))
        )
        .filter(_.last == nestedBuildFileName)

      val adjacentScripts = (projectRoot +: buildFiles.map(_ / os.up))
        .flatMap(os.list(_))
        .filter(_.last.endsWith(s".$buildFileExtension"))

      buildFiles ++ adjacentScripts
    }
  }

  def fileImportToSegments(base: os.Path, s: os.Path): Seq[String] = {
    val rel = s.relativeTo(base)
    Seq(globalPackagePrefix) ++ Seq.fill(rel.ups)("^") ++ rel.segments
  }
}
