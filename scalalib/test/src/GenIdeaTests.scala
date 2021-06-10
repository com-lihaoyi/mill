package mill.scalalib

import mill.util.ScriptTestSuite
import os.Path
import utest.assert

import java.util.regex.Pattern
import utest.{Tests, _}

object GenIdeaTests extends ScriptTestSuite(false) {

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
    val resourceString =
      scala.io.Source.fromResource(resourcePath).getLines().mkString("\n")
    val generatedString =
      normaliseLibraryPaths(os.read(generated), fileBaseDir)

    resourceString.split(Pattern.quote("<!-- IGNORE -->")) match {
      case Array(fullContent) =>
        assert(!resourcePath.toString.isEmpty && generatedString == fullContent)
      // case Array(start, end) =>
      // assert(generatedString.startsWith(start))
      // assert(generatedString.endsWith(end))
      case Array(start, rest @ _*) =>
        // We ignore parts of the generated file
        assert(
          !resourcePath.toString.isEmpty && generatedString.startsWith(start)
        )
        rest.toList.reverse match {
          case end :: middle =>
            assert(
              !resourcePath.toString.isEmpty && generatedString.endsWith(end)
            )
            middle.foreach { contentPart =>
              assert(
                !resourcePath.toString.isEmpty &&
                  generatedString.contains(contentPart.trim())
              )
            }
        }
    }

  }

  def tests: Tests = Tests {
    "genIdeaTests" - {
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
        os.Path(coursier.paths.CoursierPaths.cacheDirectory())
          .relativeTo(workspacePath),
      "COURSIER_HOME"
    )
  }

  override def workspaceSlug: String = "gen-idea-hello-world"

  override def scriptSourcePath: Path =
    os.pwd / "scalalib" / "test" / "resources" / workspaceSlug
}
