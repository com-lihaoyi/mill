package mill.integration
package local

import os.Path
import utest.{Tests, assert, _}

import java.util.regex.Pattern
import scala.util.Try

object GenIdeaTests extends ScriptTestSuite(false) {

  override def workspaceSlug: String = "gen-idea-hello-world"

  override def scriptSourcePath: Path =
    os.pwd / "integration" / "resources" / workspaceSlug

  private val scalaVersionLibPart = "2_12_5"

  private val ignoreString = "<!-- IGNORE -->"

  /**
   * The resource content will loaded from the claspath and matched against the file.
   * It may contain the `<!-- IGNORE -->` String, to simulate wildcard-matches.
   */
  def assertIdeaXmlResourceMatchesFile(
      workspaceSlug: String,
      fileBaseDir: os.Path,
      resource: os.RelPath
  ): Unit = {
    val expectedResourcePath = s"$workspaceSlug/idea/$resource"
    val actualResourcePath = fileBaseDir / ".idea" / resource

    val expectedResourceString = scala.io.Source.fromResource(expectedResourcePath).getLines.mkString("\n")
    val actualResourceString = normaliseLibraryPaths(os.read(actualResourcePath), fileBaseDir)

    assert(expectedResourcePath.nonEmpty)
    assertPartialContentMatches(
      found = actualResourceString,
      expected = expectedResourceString
    )
  }

  def assertPartialContentMatches(
      found: String,
      expected: String
  ): Unit = {
    if (!expected.contains(ignoreString)) {
      assert(found == expected)
    }

    val pattern =
      "(?s)^\\Q" + expected.replaceAll(Pattern.quote(ignoreString), "\\\\E.*\\\\Q") + "\\E$"
    assert(Pattern.compile(pattern).matcher(found).matches())
  }

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
      eval("mill.scalalib.GenIdea/idea")

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
            workspaceSlug,
            workspacePath,
            resource
          )
        }
      }
      assert(checks.forall(_.isSuccess))
    }
  }

  private def normaliseLibraryPaths(in: String, workspacePath: os.Path): String = {
    val coursierPath = os.Path(coursier.paths.CoursierPaths.cacheDirectory())
    val path =
      Try("$PROJECT_DIR$/" + coursierPath.relativeTo(workspacePath)).getOrElse(
        coursierPath
      ).toString().replace(
        """\""",
        "/"
      )
    in.replace(path, "COURSIER_HOME")
  }
}
