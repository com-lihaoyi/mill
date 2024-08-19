package mill.integration

import mill.testkit.IntegrationTestSuite

import mill.testkit.IntegrationTestSuite
import utest._

import scala.util.Try
import os.Path

object GenIdeaExtendedTests extends IntegrationTestSuite {

  override def scriptSourcePath: Path = super.scriptSourcePath / "extended"

  def tests: Tests = Tests {
    "genIdeaTests" - {
      val workspacePath = initWorkspace()
      val expectedBase = workspacePath / "idea"
      val resources = os.walk(expectedBase).filter(os.isFile).map(_.subRelativeTo(expectedBase))

      eval("mill.idea.GenIdea/idea")

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
