package mill.javalib

import mill.javalib.given
import utest.*

class DepTests extends TestSuite {
  val tests = Tests {

    test("validatePlatformDeps") {
      test("empty") {
        val deps = Seq()
        assert(Dep.validatePlatformDeps("_s", deps) == Seq())
      }
      test("platform-only") {
        val msg = Dep.validatePlatformDeps(
          "_s",
          Seq(
            mvn"org1::name1::1",
            mvn"org2::name2::1"
          )
        )
        assertGoldenLiteral(msg, List())
      }
      test("platform-missing") {
        val msg = Dep.validatePlatformDeps(
          "_s",
          Seq(
            mvn"org1::name1::1",
            mvn"org2::name2:1" // wrong
          )
        )
        assertGoldenLiteral(
          msg,
          List(
            "Detected 1 (out of 2) non-platform dependencies. This if often an error due to a missing second colon (:) before the version.",
            "Found org2::name2:1, did you mean org2::name2::1 ?"
          )
        )
        msg
      }
      test("platform-missing-full") {
        val msg = Dep.validatePlatformDeps(
          "_s",
          Seq(
            mvn"org1:::name1::1", // wrong
            mvn"org2:::name2:1" // wrong
          )
        )
        assertGoldenLiteral(
          msg,
          List(
            "Detected 1 (out of 2) non-platform dependencies. This if often an error due to a missing second colon (:) before the version.",
            "Found org2:::name2:1, did you mean org2:::name2::1 ?"
          )
        )
        msg
      }
      test("mixed") {
        val msg = Dep.validatePlatformDeps(
          "_s",
          Seq(
            mvn"org1::name1::1",
            mvn"org2::name2:1", // wrong
            mvn"org3:name3:1", // wrong
            mvn"org4:name4_s:1" // explicit platfrom
          )
        )
        assertGoldenLiteral(
          msg,
          List(
            "Detected 2 (out of 4) non-platform dependencies. This if often an error due to a missing second colon (:) before the version.",
            "Found org2::name2:1, did you mean org2::name2::1 ?",
            "Found org3:name3:1, did you mean org3:name3::1 ?"
          )
        )
        msg
      }
      test("no-platform-suffix") {
        val msg = Dep.validatePlatformDeps(
          "",
          Seq(
            mvn"org1::name1::1",
            mvn"org2::name2:1",
            mvn"org3:name3:1"
          )
        )
        assert(msg == Seq())
        msg
      }
    }
  }
}
