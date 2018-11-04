package mill.main

import mill.util.ScriptTestSuite
import utest._

object ForeignBuildsTest extends ScriptTestSuite(fork = false) {
  def workspaceSlug = "foreign-builds"
  def scriptSourcePath =
    os.pwd / 'main / 'test / 'resources / 'examples / 'foreign
  override def buildPath = os.rel / 'project / "build.sc"

  val tests = Tests {
    initWorkspace()
    'test - {
      // See https://github.com/lihaoyi/mill/issues/302
      if (!ammonite.util.Util.java9OrAbove) {
        assert(
          eval("checkProjectPaths"),
          eval("checkInnerPaths"),
          eval("checkOuterPaths"),
          eval("checkOuterInnerPaths"),
          eval("checkProjectDests"),
          eval("checkInnerDests"),
          eval("checkOuterDests"),
          eval("checkOuterInnerDests")
        )
      }
    }
  }
}
