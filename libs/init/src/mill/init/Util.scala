package mill.init
import mill.constants.OutFiles
import mill.constants.CodeGenConstants.buildFileExtensions
object Util {

  def scalafmtConfigFile: os.Path =
    os.temp(
      """version = "3.8.4"
        |runner.dialect = scala213
        |newlines.source=fold
        |newlines.topLevelStatementBlankLines = [
        |  {
        |    blanks { before = 1 }
        |  }
        |]
        |""".stripMargin
    )

  def buildFiles(workspace: os.Path): geny.Generator[os.Path] = {
    val outDir = workspace / os.RelPath(OutFiles.out)
    val bspOutDir = workspace / os.RelPath(OutFiles.bspOut)

    os.walk.stream(workspace, skip = path => path == outDir || path == bspOutDir)
      .filter(file => buildFileExtensions.contains(file.ext))
  }

}
