package mill.entrypoint

import utest._

object ForeignConflictTest extends ScriptTestSuite(fork = false) {
  def workspaceSlug = "foreign-conflict"
  def scriptSourcePath =
    os.pwd / "main" / "test" / "resources" / "examples" / "foreign"
  override def buildPath = os.sub / "conflict" / "build.sc"

  val tests = Tests {
    initWorkspace()
    "test" - {
      // see https://github.com/lihaoyi/mill/issues/302
      if (!mill.util.Util.java9OrAbove) {
        assert(
          eval("checkPaths"),
          eval("checkDests")
        )
      }
    }
  }
}
