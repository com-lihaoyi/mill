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
      val expectedBase = tester.workspaceSourcePath / "idea"
      val resources = os.walk(expectedBase).filter(os.isFile).map(_.subRelativeTo(expectedBase))
      eval("version", check = true, stdout = os.Inherit, stderr = os.Inherit)
      eval("mill.idea.GenIdea/", check = true, stdout = os.Inherit, stderr = os.Inherit)

      for (resource <- resources)
        assertIdeaXmlResourceMatchesFile(tester.workspaceSourcePath, workspacePath, resource)
    }
  }

}
