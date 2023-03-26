package mill.integration
package local

import utest._

object ForeignConflictTest extends IntegrationTestSuite("foreign-conflict", fork = false) {
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
