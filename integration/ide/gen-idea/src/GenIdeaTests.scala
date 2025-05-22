package mill.integration

import utest.{Tests, assert, _}

import mill.testkit.UtestIntegrationTestSuite
import GenIdeaUtils._
import os.Path

object GenIdeaTests extends UtestIntegrationTestSuite {

  override def workspaceSourcePath: Path = super.workspaceSourcePath / "hello-idea"

  def tests: Tests = Tests {
    test("helper partialContentMatches works") - integrationTest { tester =>
      val testContent =
        s"""line 1
           |line 2
           |line 3
           |line 4
           |""".stripMargin

      assert(partialContentMatches(testContent, testContent))
      intercept[utest.AssertionError] {
        assert(partialContentMatches(testContent, "line 1"))
      }
      assert(
        partialContentMatches(
          found = testContent,
          expected =
            s"""line 1${ignoreString}line 4
               |""".stripMargin
        )
      )
      intercept[utest.AssertionError] {
        assert(
          partialContentMatches(
            found = testContent,
            expected =
              s"""line 1${ignoreString}line 2${ignoreString}line 2${ignoreString}line 4
                 |""".stripMargin
          )
        )
      }
      assert(
        partialContentMatches(
          found = testContent,
          expected = s"line 1${ignoreString}line 2$ignoreString"
        )
      )
      intercept[utest.AssertionError] {
        assert(
          partialContentMatches(
            found = testContent,
            expected = s"line 1${ignoreString}line 2${ignoreString}line 2$ignoreString"
          )
        )
      }
      ()
    }

    test("genIdeaTests") - integrationTest { tester =>
      import tester._
      val expectedBase = tester.workspaceSourcePath / "idea"
      val resources = os.walk(expectedBase).filter(os.isFile).map(_.subRelativeTo(expectedBase))

      eval("mill.idea/")

      for (resource <- resources)
        assertIdeaXmlResourceMatchesFile(tester.workspaceSourcePath, workspacePath, resource)
    }
  }

}
