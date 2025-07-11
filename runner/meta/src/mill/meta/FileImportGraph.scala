package mill.meta

import mill.api.daemon.internal.internal
import mill.constants.CodeGenConstants.*
import mill.constants.OutFiles.*
import mill.api.daemon.internal.MillScalaParser
import scala.collection.mutable
import scala.jdk.CollectionConverters.CollectionHasAsScala
import mill.internal.Util.backtickWrap

/**
 * @param seenScripts
 * @param repos
 * @param mvnDeps
 * @param importGraphEdges
 * @param errors
 * @param metaBuild If `true`, a meta-build is enabled
 */
@internal
case class FileImportGraph(
    seenScripts: Map[os.Path, String],
    errors: Seq[String],
    buildFile: String,
    headerData: String
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
      topLevelProjectRoot: os.Path,
      projectRoot: os.Path,
      output: os.Path,
      parser: MillScalaParser = MillScalaParser.current.value
  ): FileImportGraph = {
    val seenScripts = mutable.Map.empty[os.Path, String]
    val errors = mutable.Buffer.empty[String]

    def processScript(s: os.Path, useDummy: Boolean = false): Unit =
      try {

        val content = if (useDummy) "" else os.read(s)
        val fileName = s.relativeTo(topLevelProjectRoot).toString
        val buildHeaderError =
          if (useDummy) Right(())
          else
            try Right(mill.constants.Util.readBuildHeader(s.toNIO, s.last))
            catch { case e: RuntimeException => Left(e.getMessage) }

        buildHeaderError.flatMap(_ =>
          parser.splitScript(content, fileName)
        ) match {
          case Right((prefix, pkgs, stmts)) =>
            val importSegments = pkgs.mkString(".")

            val expectedImportSegments0 =
              Seq(rootModuleAlias) ++ (s / os.up).relativeTo(projectRoot).segments

            val expectedImportSegments = expectedImportSegments0.map(backtickWrap).mkString(".")
            if (
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
            seenScripts(s) = prefix + stmts.mkString
          case Left(error) =>
            seenScripts(s) = ""
            errors.append(error)
        }
      } catch {
        case ex: Throwable =>
          seenScripts(s) = ""
          pprint.log(ex.getStackTrace.mkString("\n"))
          errors.append(ex.getClass.getName + " " + ex.getMessage)
      }

    val (useDummy, foundRootBuildFileName) = findRootBuildFiles(projectRoot)

    processScript(projectRoot / foundRootBuildFileName, useDummy)

    walkBuildFiles(projectRoot, output).foreach(processScript(_))

    val headerData =
      if (!os.exists(projectRoot / foundRootBuildFileName)) ""
      else mill.constants.Util.readBuildHeader(
        (projectRoot / foundRootBuildFileName).toNIO,
        foundRootBuildFileName
      )

    new FileImportGraph(
      seenScripts.toMap,
      errors.toSeq,
      foundRootBuildFileName,
      headerData
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
