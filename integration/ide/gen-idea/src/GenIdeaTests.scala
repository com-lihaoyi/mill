package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import GenIdeaUtils._
import os.Path
import utest._

object GenIdeaTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "hello-idea"

  def tests: Tests = Tests {
    test("genIdeaTests") - integrationTest { tester =>
      import tester._
      val expectedBase = tester.workspaceSourcePath / "idea"
      val resources = os.walk(expectedBase).filter(os.isFile).map(_.subRelativeTo(expectedBase))

      eval("mill.idea/", check = true, stdout = os.Inherit, stderr = os.Inherit)

      for (resource <- resources)
        if (resource.last.endsWith(".lines")) {
          assertFileContainsLines(tester.workspaceSourcePath, workspacePath, resource, ".lines")
        } else {
          assertIdeaXmlResourceMatchesFile(tester.workspaceSourcePath, workspacePath, resource)
        }
    }
  }

}
