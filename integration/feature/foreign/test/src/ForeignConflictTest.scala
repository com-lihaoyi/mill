package mill.integration
package local

import utest._
import os.SubPath

object ForeignConflictTest extends IntegrationTestSuite {

  override def buildPath: SubPath = os.sub / "conflict" / "build.sc"

  val tests: Tests = Tests {
    initWorkspace()
    test("test") - {
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
