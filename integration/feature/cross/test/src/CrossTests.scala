package mill.integration

import mill.api.PathRef
import utest._
import utest.framework.TestPath

object CrossTests extends IntegrationTestSuite {
  val tests = Tests {
    def testCrossSourcePath(expectedPath: os.SubPath)(implicit tp: TestPath): Unit = {
      val target = tp.value.last
      val res = eval(target)
      assert(res == true)
      val value = ujson.read(meta(target))("value")
      val topCrossAPath = upickle.default.read[PathRef](value)
      assert(
        topCrossAPath.path == wd / expectedPath
      )
    }

    initWorkspace()
    "topCross[a].path" - testCrossSourcePath(os.sub / "topCross")
    "topCross2[a,1].path" - testCrossSourcePath(os.sub / "topCross2")
    "topCrossU[a].path" - testCrossSourcePath(os.sub / "topCrossU" / "a")
    "topCross2U[a,1].path" - testCrossSourcePath(os.sub / "topCross2U" / "a" / "1")
  }
}
