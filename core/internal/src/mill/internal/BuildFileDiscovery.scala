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

      // Filter out build files discovered by following the `out/mill-workspace` and
      // `out/mill-home` alias symlinks set up for reproducible builds; otherwise we
      // would re-discover the same build files under their aliased paths.
      val daemonSandboxWorkspace = output / "mill-daemon" / "sandbox" / "out" / "mill-workspace"
      def isNoDaemonSandboxWorkspace(path: os.Path): Boolean = {
        path.startsWith(output / "mill-no-daemon") &&
        path.toString.replace('\\', '/').contains("/sandbox/out/mill-workspace")
      }
      def isAliasWorkspaceTree(path: os.Path): Boolean = {
        val normalized = path.toString.replace('\\', '/')
        normalized.contains("/out/mill-workspace/") || normalized.endsWith("/out/mill-workspace") ||
        normalized.contains("/out/mill-home/") || normalized.endsWith("/out/mill-home")
      }
      (buildFiles ++ adjacentScripts)
        .filterNot(p =>
          p.startsWith(daemonSandboxWorkspace) || isNoDaemonSandboxWorkspace(
            p
          ) || (
            (p.startsWith(output / "mill-daemon") || p.startsWith(output / "mill-no-daemon")) &&
              isAliasWorkspaceTree(p)
          )
        )
        .distinct
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
