package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

import os.Path
import mill.integration.GenIdeaUtils.assertIdeaFolderMatches

object GenIdeaExtendedTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "extended"

  def tests: Tests = Tests {
    test("genIdeaTests") - integrationTest { tester =>
      import tester.*
      eval("version", check = true, stdout = os.Inherit, stderr = os.Inherit)
      eval("mill.idea.GenIdea/", check = true, stdout = os.Inherit, stderr = os.Inherit)

      assertIdeaFolderMatches(tester.workspaceSourcePath, workspacePath)
    }
  }
}
