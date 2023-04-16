package mill.scalalib.api

import utest._

object ZincWorkerUtilTests extends TestSuite {
  val tests = Tests {
    test("matchingVersions") {
      val res = ZincWorkerUtil.matchingVersions("2.12.12")

      val exp = Seq("2.12.12", "2.12", "2")

      assert(res == exp)
    }
    test("versionRanges") {
      val res = ZincWorkerUtil.versionRanges(
        "2.12.12",
        Seq("2.11.12", "2.12.12", "2.13.4", "3.0.0-RC1")
      )

      val exp = Seq(
        "2.11.12+",
        "2.11+",
        "2+",
        "2.12.12+",
        "2.12+",
        "2-",
        "2.12.12-",
        "2.12-",
        "2.13.4-",
        "2.13-",
        "3.0-",
        "3-"
      )

      assert(res == exp)
    }
  }
}
