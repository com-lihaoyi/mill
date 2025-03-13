package mill.main.buildgen

import mill.constants.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames}
import mill.constants.OutFiles

object BuildGenUtil:

  def buildFiles(workspace: os.Path = os.pwd): Seq[os.Path] =
    os.walk(workspace, skip = (workspace / OutFiles.out).equals)
      .filter(_.lastOpt.exists(name =>
        rootBuildFileNames.contains(name) || nestedBuildFileNames.contains(name)
      ))
