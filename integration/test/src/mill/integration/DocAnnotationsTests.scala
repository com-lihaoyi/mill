package mill.integration

import ammonite.ops.{Path, pwd}
import mill.util.ScriptTestSuite
import utest._

class DocAnnotationsTests(fork: Boolean) extends ScriptTestSuite(fork) {
  def workspaceSlug: String = "docannotations"
  def scriptSourcePath: Path = pwd / 'integration / 'test / 'resources / workspaceSlug
  val tests = Tests{
    initWorkspace()
    'test - {
      assert(eval("resolve", "_"))
      assert(eval("doc", "core.test.ivyDeps"))
      val inheritedIvyDeps = ujson.read(meta("doc")).str
      assert(
        inheritedIvyDeps.contains("build.core.test.ivyDeps"),
        inheritedIvyDeps.contains("Overriden ivyDeps Docs!!!"),
        inheritedIvyDeps.contains("mill.scalalib.JavaModule.Tests.ivyDeps"),
        inheritedIvyDeps.contains("Any ivy dependencies you want to add to this Module"),
      )

      assert(eval("doc core"))
      val module = ujson.read(meta("doc")).str
      assert(
        module.contains("The Core Module Docz!")
      )

      assert(eval("core.task"))
      val task = ujson.read(meta("doc")).str
      assert(
        task.contains("Core Task Docz!")
      )

      assert(eval("doc doc"))
      val doc = ujson.read(meta("doc")).str
      assert(
        doc.contains("Shows the documentation for the specific task")
      )
    }
  }
}
