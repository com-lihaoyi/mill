package mill.internal

import mill.constants.CodeGenConstants.*
import mill.constants.ConfigConstants
import mill.constants.OutFiles.OutFiles.millBuild

import scala.jdk.CollectionConverters.CollectionHasAsScala

object BuildFileDiscovery {
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

  def walkNestedBuildFiles(projectRoot: os.Path, output: os.Path): Seq[os.Path] = {
    if (!os.exists(projectRoot)) Nil
    else {
      val buildFileNames = nestedBuildFileNamesFor(projectRoot)

      os.walk(
        projectRoot,
        followLinks = true,
        skip = p =>
          p == output ||
            p == projectRoot / millBuild ||
            (os.isDir(p) && !buildFileNames.exists(n => os.exists(p / n)))
      ).filter(p => buildFileNames.contains(p.last))
    }
  }

  def walkBuildFiles(projectRoot: os.Path, output: os.Path): Seq[os.Path] = {
    if (!os.exists(projectRoot)) Nil
    else {
      val buildFiles = walkNestedBuildFiles(projectRoot, output)
      val adjacentScripts = (projectRoot +: buildFiles.map(_ / os.up))
        .flatMap(os.list(_))
        .filter(p =>
          buildFileExtensions.asScala.exists(ext =>
            p.baseName.nonEmpty && p.last.endsWith("." + ext)
          )
        )

      (buildFiles ++ adjacentScripts).distinct
    }
  }

  private def nestedBuildFileNamesFor(projectRoot: os.Path): Seq[String] = {
    val allowNestedBuildMillFiles = Util.readBooleanFromBuildHeader(
      projectRoot,
      ConfigConstants.millAllowNestedBuildMill,
      rootBuildFileNames.asScala.toSeq
    )

    val packageBuildFileNames = nestedBuildFileNames.asScala.toSeq
    if (allowNestedBuildMillFiles) packageBuildFileNames ++ rootBuildFileNames.asScala.toSeq
    else packageBuildFileNames
  }
}
