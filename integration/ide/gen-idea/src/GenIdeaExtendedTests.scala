package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

import os.Path

object GenIdeaExtendedTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "extended"

  def tests: Tests = Tests {
    test("genIdeaTests") - integrationTest { tester =>
      import tester._
      val expectedBase = workspacePath / "idea"
      val resources = os.walk(expectedBase).filter(os.isFile).map(_.subRelativeTo(expectedBase))

      eval("mill.idea.GenIdea/")

      for (resource <- resources) {
        GenIdeaUtils.assertIdeaXmlResourceMatchesFile(
          tester.workspaceSourcePath,
          workspacePath,
          resource
        )
      }
    }
  }
}
