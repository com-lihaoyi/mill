package mill.integration
package local

import utest._
import utest.framework.TestPath
import os.SubPath

object ForeignBuildsTest extends IntegrationTestSuite {
  override def buildPath: SubPath = os.sub / "project" / "build.sc"

  val tests: Tests = Tests {
    initWorkspace()
    def checkTarget()(implicit testPath: TestPath): Unit = assert(eval(testPath.value.last))
    "checkProjectPaths" - checkTarget()
    "checkInnerPaths" - checkTarget()
    "checkOuterPaths" - checkTarget()
    "checkOuterInnerPaths" - checkTarget()
    "checkOtherPaths" - checkTarget()
    "checkProjectDests" - checkTarget()
    "checkInnerDests" - checkTarget()
    "checkOuterDests" - checkTarget()
    "checkOuterInnerDests" - checkTarget()
    "checkOtherDests" - checkTarget()
  }
}
