package mill.main

import mill.util.ScriptTestSuite
import utest._
import utest.framework.TestPath

object ForeignBuildsTest extends ScriptTestSuite(fork = false) {
  def workspaceSlug = "foreign-builds"
  def scriptSourcePath =
    os.pwd / "main" / "test" / "resources" / "examples" / "foreign"
  override def buildPath = os.sub / "project" / "build.sc"

  val tests = Tests {
    initWorkspace()
    def checkTarget()(implicit testPath: TestPath): Unit = assert(eval(testPath.value.last))
    "test" - {
      "checkProjectPaths" - checkTarget()
      "checkInnerPaths" - checkTarget()
      "checkOuterPaths" - checkTarget()
      "checkOuterInnerPaths" - checkTarget()
      "checkProjectDests" - checkTarget()
      "checkInnerDests" - checkTarget()
      "checkOuterDests" - checkTarget()
      "checkOuterInnerDests" - checkTarget()
    }
  }
}
