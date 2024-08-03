package mill.integration.local

import utest.{Tests, assert, _}

import scala.util.Try
import mill.integration.IntegrationTestSuite
import GenIdeaUtils._
import os.Path

object GenIdeaTests extends IntegrationTestSuite {

  override def scriptSourcePath: Path = super.scriptSourcePath / "hello-idea"

  def tests: Tests = Tests {
    test("helper assertPartialContentMatches works") {
      val testContent =
        s"""line 1
           |line 2
           |line 3
           |line 4
           |""".stripMargin

      assertPartialContentMatches(testContent, testContent)
      intercept[utest.AssertionError] {
        assertPartialContentMatches(testContent, "line 1")
      }
      assertPartialContentMatches(
        found = testContent,
        expected =
          s"""line 1${ignoreString}line 4
             |""".stripMargin
      )
      intercept[utest.AssertionError] {
        assertPartialContentMatches(
          found = testContent,
          expected =
            s"""line 1${ignoreString}line 2${ignoreString}line 2${ignoreString}line 4
               |""".stripMargin
        )
      }
      assertPartialContentMatches(
        found = testContent,
        expected = s"line 1${ignoreString}line 2$ignoreString"
      )
      intercept[utest.AssertionError] {
        assertPartialContentMatches(
          found = testContent,
          expected = s"line 1${ignoreString}line 2${ignoreString}line 2$ignoreString"
        )
      }
      ()
    }

    test("genIdeaTests") {
      val workspacePath = initWorkspace()
      val expectedBase = workspacePath / "idea"
      val resources = os.walk(expectedBase).filter(os.isFile).map(_.subRelativeTo(expectedBase))

      eval("mill.idea.GenIdea/idea")

      val checks = resources.map { resource =>
        Try {
          assertIdeaXmlResourceMatchesFile(
            workspacePath,
            resource
          )
        }
      }
      assert(checks.forall(_.isSuccess))
    }
  }

}
