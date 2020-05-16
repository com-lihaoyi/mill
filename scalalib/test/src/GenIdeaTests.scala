package mill.scalalib

import mill.util.ScriptTestSuite
import os.Path
import utest._

object GenIdeaTests extends ScriptTestSuite(false) {

  def tests: Tests = Tests {
    'genIdeaTests - {
      val workspacePath = initWorkspace()
      eval("mill.scalalib.GenIdea/idea")

      Seq(
        s"$workspaceSlug/idea_modules/helloworld.iml" ->
          workspacePath / ".idea_modules" /"helloworld.iml",
        s"$workspaceSlug/idea_modules/helloworld.test.iml" ->
          workspacePath / ".idea_modules" /"helloworld.test.iml",
        s"$workspaceSlug/idea/libraries/scala_library_2_12_4_jar.xml" ->
          workspacePath / ".idea" / "libraries" / "scala_library_2_12_4_jar.xml",
        s"$workspaceSlug/idea/modules.xml" ->
          workspacePath / ".idea" / "modules.xml",
        s"$workspaceSlug/idea/misc.xml" ->
          workspacePath / ".idea" / "misc.xml"
      ).foreach { case (resource, generated) =>
          val resourceString = scala.io.Source.fromResource(resource).getLines().mkString("\n")
          val generatedString = normaliseLibraryPaths(os.read(generated), workspacePath)

          assert(resourceString == generatedString)
        }
    }
  }

  private def normaliseLibraryPaths(in: String, workspacePath: os.Path): String = {

    in.replace(
      "$PROJECT_DIR$/" +
      os.Path(coursier.paths.CoursierPaths.cacheDirectory()).relativeTo(workspacePath),
      "COURSIER_HOME"
    )
  }

  override def workspaceSlug: String = "gen-idea-hello-world"

  override def scriptSourcePath: Path = os.pwd / 'scalalib / 'test / 'resources / workspaceSlug
}
