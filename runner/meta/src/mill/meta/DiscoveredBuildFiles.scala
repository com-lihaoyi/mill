package mill.meta

import mill.api.daemon.internal.internal
import mill.api.daemon.Result
import mill.constants.CodeGenConstants.*
import mill.constants.OutFiles.OutFiles.*
import mill.api.daemon.internal.MillScalaParser
import scala.collection.mutable
import scala.jdk.CollectionConverters.CollectionHasAsScala
import mill.internal.Util.backtickWrap

/**
 * @param seenScripts Map of script paths to their processed content
 */
@internal
case class DiscoveredBuildFiles(seenScripts: Map[os.Path, String])

/**
 * Logic around traversing the `import $file` graph, extracting necessary info
 * and converting it to a convenient data structure for downstream code to use
 */
@internal
object DiscoveredBuildFiles {

  import mill.api.JsonFormatters.pathReadWrite
  implicit val readWriter: upickle.ReadWriter[DiscoveredBuildFiles] = upickle.macroRW

  /**
   * We perform a depth-first traversal of the import graph of `.mill` files,
   * starting from `build.mill`, collecting the information necessary to
   * instantiate the [[MillRootModule]]
   */
  def parseBuildFiles(
      topLevelProjectRoot: os.Path,
      projectRoot: os.Path,
      output: os.Path,
      parser: MillScalaParser,
      walked: Seq[os.Path],
      colored: Boolean
  ): DiscoveredBuildFiles = {
    val seenScripts = mutable.Map.empty[os.Path, String]
    val errors = mutable.Buffer.empty[Result.Failure]

    def processScript(s: os.Path): Unit =
      try {
        val content = os.read(s)
        val fileName = s.relativeTo(topLevelProjectRoot).toString
        val buildHeaderError =
          try Right(mill.constants.Util.readBuildHeader(s.toNIO, s.last))
          catch { case e: RuntimeException => Left(e.getMessage) }

        if (s.last.endsWith(".yaml")) seenScripts(s) = os.read(s)
        else buildHeaderError.flatMap(_ => parser.splitScript(content, fileName, colored)) match {
          case Right((prefix, pkgs, stmts)) =>
            val importSegments = pkgs.mkString(".")

            val expectedImportSegments =
              (Seq(rootModuleAlias) ++ (s / os.up).relativeTo(projectRoot).segments)
                .map(backtickWrap)
                .mkString(".")

            val isRootFile = rootBuildFileNames.contains(s.last) && (s / os.up) == projectRoot
            val isBuildFile = buildFileExtensions.asScala.exists(ext => s.last == s"build.$ext")

            val packageDeclarationValid = (isRootFile, isBuildFile) match {
              case (true, _) =>
                importSegments == "" || expectedImportSegments == importSegments
              case (_, true) =>
                // Nested build.mill: relaxed validation (allow prefixes)
                importSegments == "" ||
                importSegments == "build" ||
                expectedImportSegments.startsWith(importSegments + ".") ||
                expectedImportSegments == importSegments
              case _ =>
                // Nested package.mill: strict validation
                expectedImportSegments == importSegments
            }

            if (!packageDeclarationValid) {
              val expectedImport =
                if (expectedImportSegments.isEmpty) "<none>"
                else s"\"package $expectedImportSegments\""
              errors.append(
                s"Package declaration \"package $importSegments\" in " +
                  s"${s.relativeTo(topLevelProjectRoot)} does not match " +
                  s"folder structure. Expected: $expectedImport"
              )
            }
            seenScripts(s) = prefix + stmts.mkString
          case Left(error) =>
            seenScripts(s) = ""
            // Error is already formatted with position info from the parser
            errors.append(Result.Failure(error))
        }
      } catch {
        case ex: Throwable =>
          seenScripts(s) = ""
          errors.append(Result.Failure(ex.getClass.getName + " " + ex.getMessage))
      }

    val (isDummy, foundRootBuildFileName) = findRootBuildFiles(projectRoot)

    val foundRootBuildFile = projectRoot / foundRootBuildFileName
    // For dummy builds (no build.mill), skip processing the non-existent file
    if (!isDummy) processScript(foundRootBuildFile)

    walked.filter(_ != foundRootBuildFile).foreach(processScript(_))

    if (errors.nonEmpty) {
      val joinedFailure = Result.Failure.join(errors.toSeq)
      throw Result.Exception(joinedFailure.error, Some(joinedFailure))
    }

    new DiscoveredBuildFiles(seenScripts.toMap)
  }

  def findRootBuildFiles(projectRoot: os.Path) = {
    val rootBuildFiles = rootBuildFileNames.asScala
      .filter(rootBuildFileName => os.exists(projectRoot / rootBuildFileName))

    val (dummy, foundRootBuildFileName) = rootBuildFiles.toSeq match {
      case Nil => (true, "build.mill")
      case Seq(single) => (false, single)
      case multiple =>
        System.err.println(
          "Multiple root build files found: " + multiple.mkString(",") +
            ", picking " + multiple.head
        )
        (false, multiple.head)
    }

    (dummy = dummy, foundRootBuildFileName = foundRootBuildFileName)
  }

  def walkBuildFiles(projectRoot: os.Path, output: os.Path): Seq[os.Path] = {
    if (!os.exists(projectRoot)) Nil
    else {
      val nestedBuildFileNames = allNestedBuildFileNames.asScala.toSet

      val buildFiles = os
        .walk(
          projectRoot,
          followLinks = true,
          skip = p =>
            p == output ||
              p == projectRoot / millBuild ||
              (os.isDir(p) && !nestedBuildFileNames.exists(n => os.exists(p / n)))
        )
        .filter(p => nestedBuildFileNames.contains(p.last))

      val adjacentScripts = (projectRoot +: buildFiles.map(_ / os.up))
        .flatMap(os.list(_))
        .filter(p => buildFileExtensions.asScala.exists(ext => p.last.endsWith("." + ext)))

      buildFiles ++ adjacentScripts
    }
  }

  def fileImportToSegments(base: os.Path, s: os.Path): Seq[String] = {
    val rel = s.relativeTo(base)
    Seq(globalPackagePrefix) ++ Seq.fill(rel.ups)("^") ++ rel.segments
  }
}
