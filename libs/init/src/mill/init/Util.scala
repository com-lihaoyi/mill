package mill.init

import mill.constants.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames}
import mill.constants.OutFiles.OutFiles.{bspOut, defaultBspOut, millBuild, out}

object Util {

  def scalafmtConfig: String =
    """version = "3.8.5"
      |runner.dialect = scala3
      |newlines.source=fold
      |""".stripMargin

  def buildFiles(workspace: os.Path): Seq[os.Path] = {
    // Include `defaultBspOut` (`.bsp/out`) explicitly because `bspOut` only
    // reflects the `MILL_BSP_OUTPUT_DIR` env var; the `mill-separate-bsp-output-dir`
    // build header is parsed at runtime and doesn't set the env var, so the
    // header-only path would otherwise be walked into.
    val skip = Seq(bspOut, defaultBspOut, millBuild, out)
      .distinct.map(s => workspace / os.RelPath(s))
    os.walk.stream(workspace, skip = skip.contains).filter(path =>
      os.isFile(path) &&
        (nestedBuildFileNames.contains(path.last) || rootBuildFileNames.contains(path.last))
    ).toSeq ++ {
      val path = workspace / os.RelPath(millBuild)
      if (os.exists(path)) os.walk.stream(path).filter(os.isFile).toSeq else Nil
    }
  }
}
