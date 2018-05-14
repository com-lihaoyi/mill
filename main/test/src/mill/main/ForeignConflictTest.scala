package mill.main

import ammonite.ops._
import mill.util.ScriptTestSuite
import utest._

object ForeignConflictTest extends ScriptTestSuite(fork = false) {
  def workspaceSlug = "foreign-conflict"
  def scriptSourcePath =
    pwd / 'main / 'test / 'resources / 'examples / 'foreign
  override def buildPath = 'conflict / "build.sc"

  val tests = Tests {
    initWorkspace()
    'test - {
      // see https://github.com/lihaoyi/mill/issues/302
      if (!ammonite.util.Util.java9OrAbove) {
        assert(
          eval("checkPaths"),
          eval("checkDests")
        )
      }
    }
  }
}
