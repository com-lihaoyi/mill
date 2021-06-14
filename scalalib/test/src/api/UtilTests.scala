package mill.scalalib.api

import utest._

object UtilTests extends TestSuite {
  val tests = Tests {
    test("versionNumbers") {
      val res = Util.versionNumbers("2.12.12")

      val exp = Seq("2.12.12", "2.12", "2")

      assert(res == exp)
    }
    test("versionRanges") {
      val res = Util.versionRanges(
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
