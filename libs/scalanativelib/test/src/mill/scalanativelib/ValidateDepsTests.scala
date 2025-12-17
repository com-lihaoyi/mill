package mill.scalanativelib

import mill.scalalib.given
import mill.scalanativelib.ScalaNativeModule.validatePlatformDeps
import utest.*

class ValidateDepsTests extends TestSuite {
  val tests = Tests {
    test("empty") {
      val deps = Seq()
      assert(validatePlatformDeps(deps) == Seq())
    }
    test("platform-only") {
      val deps = Seq(
        mvn"org1::name1::1",
        mvn"org2::name2::1"
      )
      assertGoldenLiteral(
        validatePlatformDeps(deps),
        List()
      )
    }
    test("platform-missing") {
      val msg = validatePlatformDeps(Seq(
        mvn"org1::name1::1",
        mvn"org2::name2:1" // wrong
      ))
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
      val msg = validatePlatformDeps(Seq(
        mvn"org1:::name1::1", // wrong
        mvn"org2:::name2:1" // wrong
      ))
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
      val msg = validatePlatformDeps(Seq(
        mvn"org1::name1::1",
        mvn"org2::name2:1", // wrong
        mvn"org3:name3:1" // wrong
      ))
      assertGoldenLiteral(
        msg,
        List(
          "Detected 2 (out of 3) non-platform dependencies. This if often an error due to a missing second colon (:) before the version.",
          "Found org2::name2:1, did you mean org2::name2::1 ?",
          "Found org3:name3:1, did you mean org3:name3::1 ?"
        )
      )
      msg
    }

  }
}
