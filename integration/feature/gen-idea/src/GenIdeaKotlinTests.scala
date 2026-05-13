package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import GenIdeaUtils.*
import os.Path
import utest.*

object GenIdeaKotlinTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "hello-kotlin-idea"

  def tests: Tests = Tests {
    test("genIdeaKotlinTests") - integrationTest { tester =>
      import tester.*
      eval("version", check = true, stdout = os.Inherit, stderr = os.Inherit)
      eval("mill.idea.GenIdea/", check = true, stdout = os.Inherit, stderr = os.Inherit)

      assertIdeaFolderMatches(tester.workspaceSourcePath, workspacePath)
    }
  }
}
