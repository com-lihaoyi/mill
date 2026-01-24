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
      eval("mill.idea.GenIdea/", check = true, stdout = os.Inherit, stderr = os.Inherit)

      assertIdeaFolderMatches(tester.workspaceSourcePath, workspacePath)
    }
  }

}
