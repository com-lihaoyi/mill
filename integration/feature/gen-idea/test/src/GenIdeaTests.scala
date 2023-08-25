package mill.integration.local

import utest.{Tests, assert, _}

import scala.util.Try
import mill.integration.IntegrationTestSuite
import GenIdeaUtils._

object GenIdeaTests extends IntegrationTestSuite {

  override def scriptSourcePath = super.scriptSourcePath / "hello-world"
  private val scalaVersionLibPart = "2_12_5"

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
      eval("mill.idea.GenIdea/idea")

      val checks = Seq(
        os.sub / "mill_modules" / "helloworld.iml",
        os.sub / "mill_modules" / "helloworld.test.iml",
        os.sub / "mill_modules" / "mill-build.iml",
        os.sub / "libraries" / s"scala_library_${scalaVersionLibPart}_jar.xml",
        os.sub / "modules.xml",
        os.sub / "misc.xml"
      ).map { resource =>
        Try {
          assertIdeaXmlResourceMatchesFile(
            scriptSlug,
            workspacePath,
            resource
          )
        }
      }
      assert(checks.forall(_.isSuccess))
    }
  }

}
