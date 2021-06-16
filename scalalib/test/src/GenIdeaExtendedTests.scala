package mill.scalalib

import mill.util.ScriptTestSuite
import os.Path
import utest._

object GenIdeaExtendedTests extends ScriptTestSuite(false) {

  override def workspaceSlug: String = "gen-idea-extended-hello-world"

  override def scriptSourcePath: Path =
    os.pwd / "scalalib" / "test" / "resources" / workspaceSlug

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
        os.sub / "misc.xml",
        os.sub / "compiler.xml"
      ).foreach { resource =>
        GenIdeaTests.assertIdeaXmlResourceMatchesFile(
          workspaceSlug,
          workspacePath,
          resource
        )
      }
    }
  }

  private def normaliseLibraryPaths(in: String, workspacePath: os.Path): String = {
    in.replace(
      "$PROJECT_DIR$/" +
        os.Path(coursier.paths.CoursierPaths.cacheDirectory())
          .relativeTo(workspacePath),
      "COURSIER_HOME"
    )
  }

}
