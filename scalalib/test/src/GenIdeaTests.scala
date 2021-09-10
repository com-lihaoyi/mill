package mill.scalalib

import mill.util.ScriptTestSuite
import os.Path
import utest.assert

import java.util.regex.Pattern
import utest.{Tests, _}

object GenIdeaTests extends ScriptTestSuite(false) {

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
    val resourcePath = s"${workspaceSlug}/idea/${resource}"
    val generated = fileBaseDir / ".idea" / resource
    val resourceString = scala.io.Source.fromResource(resourcePath).getLines().mkString("\n")
    val generatedString = normaliseLibraryPaths(os.read(generated), fileBaseDir)
    assert(!resourcePath.isEmpty)
    assertPartialContentMatches(
      found = generatedString,
      expected = resourceString
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
        expected = "line 1" + ignoreString + "line 4\n"
      )
      intercept[utest.AssertionError] {
        assertPartialContentMatches(
          found = testContent,
          expected =
            "line 1" + ignoreString + "line 2" + ignoreString + "line 2" + ignoreString + "line 4\n"
        )
      }
      assertPartialContentMatches(
        found = testContent,
        expected = "line 1" + ignoreString + "line 2" + ignoreString
      )
      intercept[utest.AssertionError] {
        assertPartialContentMatches(
          found = testContent,
          expected = "line 1" + ignoreString + "line 2" + ignoreString + "line 2" + ignoreString
        )
      }
      ()
    }

    test("genIdeaTests") {
      val workspacePath = initWorkspace()
      eval("mill.scalalib.GenIdea/idea")

      Seq(
        os.sub / "mill_modules" / "helloworld.iml",
        os.sub / "mill_modules" / "helloworld.test.iml",
        os.sub / "mill_modules" / "mill-build.iml",
        os.sub / "libraries" / "scala_library_2_12_4_jar.xml",
        os.sub / "modules.xml",
        os.sub / "misc.xml"
      ).foreach { resource =>
        assertIdeaXmlResourceMatchesFile(
          workspaceSlug,
          workspacePath,
          resource
        )
      }
    }
  }

  private def normaliseLibraryPaths(
      in: String,
      workspacePath: os.Path
  ): String = {

    in.replace(
      "$PROJECT_DIR$/" +
        os.Path(coursier.paths.CoursierPaths.cacheDirectory()).relativeTo(workspacePath),
      "COURSIER_HOME"
    )
  }

  override def workspaceSlug: String = "gen-idea-hello-world"

  override def scriptSourcePath: Path =
    os.pwd / "scalalib" / "test" / "resources" / workspaceSlug
}
