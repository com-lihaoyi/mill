package mill.integration

import mill.util.ScriptTestSuite
import utest._

class HygieneTests(fork: Boolean)
  extends ScriptTestSuite(fork) {
  def workspaceSlug: String = "hygiene"
  def scriptSourcePath: os.Path = os.pwd / "integration" / "test" / "resources" / workspaceSlug

  val tests = Tests {
    initWorkspace()

    test {
      assert(eval("scala.foo"))
      val output = meta("scala.foo")
      assert(output.contains("\"fooValue\""))
    }

  }
}
