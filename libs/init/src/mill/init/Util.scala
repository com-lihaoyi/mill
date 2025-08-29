package mill.init
import mill.constants.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames}
import mill.constants.OutFiles.{bspOut, millBuild, out}
object Util {

  def scalafmtConfigContent: String =
    """version = "3.8.5"
      |runner.dialect = scala213
      |newlines.source=fold
      |""".stripMargin

  def buildFiles(workspace: os.Path): Seq[os.Path] = {
    val skip = Seq(bspOut, millBuild, out).map(s => workspace / os.RelPath(s))
    os.walk.stream(workspace, skip = skip.contains).filter(path =>
      os.isFile(path) &&
        (nestedBuildFileNames.contains(path.last) || rootBuildFileNames.contains(path.last))
    ).toSeq ++ {
      val path = workspace / os.RelPath(millBuild)
      if (os.exists(path)) os.walk.stream(path).filter(os.isFile).toSeq else Nil
    }
  }
}
