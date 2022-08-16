package mill.scalalib.api

import utest._

object ZincWorkerUtilTests extends TestSuite {
  val tests = Tests {
    test("pluginArtfact") {
      val res = ZincWorkerUtil.pluginArtifact("example", "0.10.5")

      val exp = "example_mill0.10"

      assert(res == exp)
    }
  }
}
