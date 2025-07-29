package mill.init
import mill.constants.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames}
import mill.constants.OutFiles.{bspOut, millBuild, out}
object Util {

  def scalafmtConfigContent: String =
    """version = "3.8.5"
      |runner.dialect = scala213
      |newlines.source=fold
      |""".stripMargin

  def scalafmtConfigFile: os.Path =
    os.temp(scalafmtConfigContent)

  def buildFiles(workspace: os.Path): Seq[os.Path] =
    os.walk.stream(
      workspace,
      skip = Seq(workspace / OutFiles.out, workspace / OutFiles.millBuild).contains
    ).filter(path =>
  def buildFiles(workspace: os.Path): Seq[os.Path] = {
    val skip = Seq(bspOut, millBuild, out).map(workspace / _)
    os.walk.stream(workspace, skip = skip.contains).filter(path =>
      os.isFile(path) &&
        (nestedBuildFileNames.contains(path.last) || rootBuildFileNames.contains(path.last))
    ).toSeq ++ (
      if (os.exists(workspace / OutFiles.millBuild))
        os.walk.stream(workspace / OutFiles.millBuild).filter(os.isFile).toSeq
    if (os.exists(workspace / millBuild))
      os.walk.stream(workspace / millBuild).filter(os.isFile).toSeq
    else Nil
    )
  }
}
