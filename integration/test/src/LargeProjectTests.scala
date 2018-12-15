package mill.integration

import mill.util.ScriptTestSuite
import utest._

class LargeProjectTests(fork: Boolean)
  extends ScriptTestSuite(fork) {
  def workspaceSlug: String = "large-project"
  def scriptSourcePath: os.Path = os.pwd / 'integration / 'test / 'resources / workspaceSlug

  val tests = Tests{
    initWorkspace()
    'test - {

      assert(eval("foo.common.one.compile"))
    }

  }
}
