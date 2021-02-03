package mill.scalalib.dependency.versions

import mill.scalalib.api.Util.scalaBinaryVersion
import utest._

object ScalaVersionTests extends TestSuite {

  val tests = Tests {
    test("release") {
      val sv = "2.13.5"
      val sbv = scalaBinaryVersion(sv)
      val expectedSbv = "2.13"
      assert(sbv == expectedSbv)
    }
    test("snapshot") {
      val sv = "2.13.6-SNAPSHOT"
      val sbv = scalaBinaryVersion(sv)
      val expectedSbv = "2.13"
      assert(sbv == expectedSbv)
    }
    test("nightly") {
      val sv = "2.13.5-bin-aab85b1"
      val sbv = scalaBinaryVersion(sv)
      val expectedSbv = "2.13"
      assert(sbv == expectedSbv)
    }
    test("dotty") {
      val sv = "0.27.3"
      val sbv = scalaBinaryVersion(sv)
      val expectedSbv = "0.27"
      assert(sbv == expectedSbv)
    }
    test("scala3") {
      val sv = "3.0.0-RC2"
      val sbv = scalaBinaryVersion(sv)
      val expectedSbv = "3.0.0-RC2"
      assert(sbv == expectedSbv)
    }
    test("typelevel") {
      val sv = "2.11.12-bin-typelevel.foo"
      val sbv = scalaBinaryVersion(sv)
      val expectedSbv = "2.11"
      assert(sbv == expectedSbv)
    }
  }

}
