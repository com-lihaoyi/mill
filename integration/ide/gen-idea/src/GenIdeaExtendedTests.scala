package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

import scala.util.Try
import os.Path

object GenIdeaExtendedTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "extended"

  def tests: Tests = Tests {
    test("genIdeaTests") - integrationTest { tester =>
      import tester._
      val expectedBase = workspacePath / "idea"
      val resources = os.walk(expectedBase).filter(os.isFile).map(_.subRelativeTo(expectedBase))

      eval("mill.idea.GenIdea/")

      val checks = resources.map { resource =>
        Try {
          GenIdeaUtils.assertIdeaXmlResourceMatchesFile(
            workspacePath,
            resource
          )
        }
      }
      assert(checks.forall(_.isSuccess))
    }
  }

}
