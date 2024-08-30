package mill.runner

import mill.api.internal
import mill.main.client.OutFiles._

import scala.reflect.NameTransformer.encode
import scala.collection.mutable

/**
 * @param seenScripts
 * @param repos
 * @param ivyDeps
 * @param importGraphEdges
 * @param errors
 * @param millImport If `true`, a meta-build is enabled
 */
@internal
case class FileImportGraph(
    seenScripts: Map[os.Path, String],
    repos: Seq[(String, os.Path)],
    ivyDeps: Set[String],
    importGraphEdges: Map[os.Path, Seq[os.Path]],
    errors: Seq[String],
    millImport: Boolean
)

/**
 * Logic around traversing the `import $file` graph, extracting necessary info
 * and converting it to a convenient data structure for downstream code to use
 */
@internal
object FileImportGraph {
  def backtickWrap(s: String): String = s match {
    case s"`$v`" => s
    case _ => if (encode(s) == s) s else "`" + s + "`"
  }

  import mill.api.JsonFormatters.pathReadWrite
  implicit val readWriter: upickle.default.ReadWriter[FileImportGraph] = upickle.default.macroRW

  /**
   * We perform a depth-first traversal of the import graph of `.sc` files,
   * starting from `build.sc`, collecting the information necessary to
   * instantiate the [[MillRootModule]]
   */
  def parseBuildFiles(topLevelProjectRoot: os.Path, projectRoot: os.Path): FileImportGraph = {
    val seenScripts = mutable.Map.empty[os.Path, String]
    val seenIvy = mutable.Set.empty[String]
    val seenRepo = mutable.ListBuffer.empty[(String, os.Path)]
    val errors = mutable.Buffer.empty[String]
    var millImport = false

    def processScript(s: os.Path, useDummy: Boolean = false): Unit = {

      val readFileEither = scala.util.Try {
        val content = if (useDummy) "" else os.read(s)
        val (segments, rest) =
          if (content.startsWith("package ")) {
            val firstLineEnd0 = content.indexOf('\n')
            val firstLineEnd = if (firstLineEnd0 == -1) content.length else firstLineEnd0
            (
              content.take(firstLineEnd).stripPrefix("package ").split("\\.", -1).toList,
              content.drop(firstLineEnd)
            )
          } else (Nil, content)

        val expectedImportSegments =
          (s / os.up).relativeTo(projectRoot).segments.map(backtickWrap).mkString(".")
        val importSegments = segments.mkString(".")
        if (expectedImportSegments != importSegments) {
          val expectedImport =
            if (expectedImportSegments.isEmpty) "<none>"
            else s"\"package $expectedImportSegments\""
          errors.append(
            s"Package declaration \"package $importSegments\" in " +
              s"${s.relativeTo(topLevelProjectRoot)} does not match " +
              s"folder structure. Expected: $expectedImport"
          )
        }
        Parsers.splitScript(rest, s.relativeTo(topLevelProjectRoot).toString)
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
          for ((stmt0, importTrees) <- Parsers.parseImportHooksWithIndices(stmts)) {
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

          case ImportTree(Seq(("$file", end0), rest @ _*), mapping, start, end) =>
            errors.append(
              s"Import $$file syntax in $s is no longer supported. Any `foo/bar.sc` file " +
              s"in a folder next to a `foo/package.sc` can be directly imported via " +
              "`import foo.bar`"
            )

            (start, "", rest.lastOption.fold(end0)(_._2))
        }
        val numNewLines = stmt.substring(start, end).count(_ == '\n')

        stmt = stmt.patch(start, patchString + mill.util.Util.newLine * numNewLines, end - start)
      }

      transformedStmts.append(stmt)
    }

    val useDummy = !os.exists(projectRoot / "build.sc")
    processScript(projectRoot / "build.sc", useDummy)
    val buildFiles = os
      .walk(
        projectRoot,
        followLinks = true,
        skip = p =>
          p == projectRoot / out ||
            p == projectRoot / millBuild ||
            (os.isDir(p) && !os.exists(p / "package.sc"))
      )
      .filter(_.last == "package.sc")

    val adjacentScripts = (projectRoot +: buildFiles.map(_ / os.up))
      .flatMap(os.list(_))
      .filter(_.ext == "sc")
    (buildFiles ++ adjacentScripts).foreach(processScript(_))

    new FileImportGraph(
      seenScripts.toMap,
      seenRepo.toSeq,
      seenIvy.toSet,
      Map(),
      errors.toSeq,
      millImport
    )
  }

  def nextPathFor(s: os.Path, rest: Seq[String]): os.Path = {
    // Manually do the foldLeft to work around bug in os-lib
    // https://github.com/com-lihaoyi/os-lib/pull/160
    val restSegments = rest
      .map {
        case "^" => os.up
        case s => os.rel / s
      }
      .foldLeft(os.rel)(_ / _)

    s / os.up / restSegments / os.up / s"${rest.last}.sc"
  }

  def fileImportToSegments(base: os.Path, s: os.Path, stripExt: Boolean): Seq[String] = {
    val rel = (s / os.up / (if (stripExt) s.baseName else s.last)).relativeTo(base)
    Seq("millbuild") ++ Seq.fill(rel.ups)("^") ++ rel.segments
  }
}
