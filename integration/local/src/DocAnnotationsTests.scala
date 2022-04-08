package mill.integration

import mill.util.ScriptTestSuite
import utest._

class DocAnnotationsTests(fork: Boolean) extends ScriptTestSuite(fork) {
  def workspaceSlug: String = "docannotations"
  override def workspacePath: os.Path = os.Path(sys.props.getOrElse("MILL_WORKSPACE_PATH", ???)) / getClass().getName()
  def scriptSourcePath: os.Path = os.pwd / "integration" / "local" / "resources" / workspaceSlug
  val tests = Tests {
    initWorkspace()
    "test" - {
      assert(eval("inspect", "core.test.ivyDeps"))
      val inheritedIvyDeps = ujson.read(meta("inspect"))("value").str
      assert(
        inheritedIvyDeps.contains("core.test.ivyDeps"),
        inheritedIvyDeps.contains("Overriden ivyDeps Docs!!!"),
        inheritedIvyDeps.contains("Any ivy dependencies you want to add to this Module")
      )

      assert(eval("inspect", "core.task"))
      val task = ujson.read(meta("inspect"))("value").str
      assert(
        task.contains("Core Task Docz!")
      )

      assert(eval("inspect", "inspect"))
      val doc = ujson.read(meta("inspect"))("value").str
      assert(
        doc.contains("Displays metadata about the given task without actually running it.")
      )
    }
  }
}
