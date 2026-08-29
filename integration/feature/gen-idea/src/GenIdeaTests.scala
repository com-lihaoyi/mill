package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import GenIdeaUtils.*
import os.Path
import utest.*

object GenIdeaTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "hello-idea"

  def tests: Tests = Tests {
    test("genIdeaTests") - integrationTest { tester =>
      import tester.*
      eval("version", check = true, stdout = os.Inherit, stderr = os.Inherit)
      val genIdeaResult = eval(
        ("--ticker", "true", "mill.idea.GenIdea/"),
        check = true,
        mergeErrIntoOut = true
      )

      val output = genIdeaResult.out
      val lastWriteIndex = output.lastIndexOf("Writing ")
      val successIndex = output.indexOf("SUCCESS]")
      assert(
        output.contains("Analyzing modules ..."),
        lastWriteIndex >= 0,
        successIndex == -1 || successIndex > lastWriteIndex
      )

      assertIdeaFolderMatches(tester.workspaceSourcePath, workspacePath)
    }
  }

}
