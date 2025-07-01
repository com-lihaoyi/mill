package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

import os.Path
import mill.integration.GenIdeaUtils.assertIdeaXmlResourceMatchesFile

object GenIdeaExtendedTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "extended"

  def tests: Tests = Tests {
    test("genIdeaTests") - integrationTest { tester =>
      import tester._
      val expectedBase = workspacePath / "idea"
      val resources = os.walk(expectedBase).filter(os.isFile).map(_.subRelativeTo(expectedBase))
      eval("version", check = true, stdout = os.Inherit, stderr = os.Inherit)
      eval("mill.idea.GenIdea/", check = true, stdout = os.Inherit, stderr = os.Inherit)

      for (resource <- resources)
        assertIdeaXmlResourceMatchesFile(tester.workspaceSourcePath, workspacePath, resource)
    }
  }
}
