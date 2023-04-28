package mill.runner

import mill.api.internal

import scala.collection.mutable

@internal
case class FileImportGraph(
    seenScripts: Map[os.Path, String],
    ivyDeps: Set[String],
    importGraphEdges: Map[os.Path, Seq[os.Path]],
    errors: Seq[String]
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
   * starting from `build.sc`, collecting the information necessary to
   * instantiate the [[MillRootModule]]
   */
  def parseBuildFiles(topLevelProjectRoot: os.Path, projectRoot: os.Path): FileImportGraph = {
    val seenScripts = mutable.Map.empty[os.Path, String]
    val seenIvy = mutable.Set.empty[String]
    val importGraphEdges = mutable.Map.empty[os.Path, Seq[os.Path]]
    val errors = mutable.Buffer.empty[String]

    def walkScripts(s: os.Path): Unit = {
      importGraphEdges(s) = Nil

      if (!seenScripts.contains(s)) {
        val readFileEither = scala.util.Try(
          Parsers.splitScript(os.read(s), s.relativeTo(topLevelProjectRoot).toString)
        ) match {
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
            val fileImports = mutable.Set.empty[os.Path]
            val transformedStmts = mutable.Buffer.empty[String]
            for ((stmt0, importTrees) <- Parsers.parseImportHooksWithIndices(stmts)) {
              walkStmt(s, stmt0, importTrees, fileImports, transformedStmts)
            }
            seenScripts(s) = transformedStmts.mkString
            fileImports.foreach(walkScripts)
        }
      }
    }

    def walkStmt(
        s: os.Path,
        stmt0: String,
        importTrees: Seq[ImportTree],
        fileImports: mutable.Set[os.Path],
        transformedStmts: mutable.Buffer[String]
    ) = {

      var stmt = stmt0
      for (importTree <- importTrees) {
        val (start, patchString, end) = importTree match {
          case ImportTree(Seq(("$ivy", _), rest @ _*), mapping, start, end) =>
            seenIvy.addAll(mapping.map(_._1))
            (start, "_root_._", end)
          case ImportTree(Seq(("$file", _), rest @ _*), mapping, start, end) =>
            val nextPaths = mapping.map { case (lhs, rhs) => nextPathFor(s, rest.map(_._1) :+ lhs) }

            fileImports.addAll(nextPaths)
            importGraphEdges(s) ++= nextPaths

            if (rest.isEmpty) (start, "_root_._", end)
            else {
              val end = rest.last._2
              (
                start,
                fileImportToSegments(projectRoot, nextPaths(0) / os.up, false).mkString("."),
                end
              )
            }
        }
        val numNewLines = stmt.substring(start, end).count(_ == '\n')
        stmt = stmt.patch(start, patchString + mill.util.Util.newLine * numNewLines, end - start)
      }

      transformedStmts.append(stmt)
    }

    walkScripts(projectRoot / "build.sc")
    new FileImportGraph(seenScripts.toMap, seenIvy.toSet, importGraphEdges.toMap, errors.toSeq)
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
