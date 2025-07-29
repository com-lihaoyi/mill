package mill.init
import mill.constants.OutFiles
import mill.constants.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames}
object Util {

  def scalafmtConfigFile: os.Path =
    os.temp(
      """version = "3.8.4"
        |runner.dialect = scala213
        |newlines.source=fold
        |""".stripMargin
    )

  def buildFiles(workspace: os.Path): geny.Generator[os.Path] =
    os.walk.stream(workspace, skip = (workspace / OutFiles.out).equals)
      .filter(file =>
        nestedBuildFileNames.contains(file.last) || rootBuildFileNames.contains(file.last)
      )

}
