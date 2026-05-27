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

      // In reproducible-build mode the daemon/no-daemon process runs in a sandbox and reaches the
      // workspace via the `out/mill-workspace` alias symlink, so `os.list`/`os.walk` return build
      // files under aliased paths (e.g. `out/mill-no-daemon/<n>/sandbox/out/mill-workspace/bar.mill`).
      // Canonicalize every discovered path through the symlinks to its real on-disk location, then
      // de-duplicate. This both keeps helper scripts (which are only ever reached via the alias) and
      // collapses any file re-discovered under both its real and aliased paths.
      def canonical(p: os.Path): os.Path =
        try os.Path(p.wrapped.toRealPath())
        catch { case _: java.io.IOException => p }
      (buildFiles ++ adjacentScripts).map(canonical).distinct
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
