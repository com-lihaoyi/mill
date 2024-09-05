package mill.contrib.errorprone

import mill.scalalib.JavaModule
import mill.testkit.{TestBaseModule, UnitTester}
import os.Path
import utest._

object ErrorProneTests extends TestSuite {

  object noErrorProne extends TestBaseModule with JavaModule {}
  object errorProne extends TestBaseModule with JavaModule with ErrorProneModule {}

  val testModuleSourcesPath: Path = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "simple"

  def tests = Tests {
    test("reference") {
      test("compile") {
        val eval = UnitTester(noErrorProne, testModuleSourcesPath)
        val res = eval(noErrorProne.compile)
        assert(res.isRight)
      }
    }
    test("errorprone") {
      test("compileFail") {
        val eval = UnitTester(errorProne, testModuleSourcesPath)
        val res = eval(errorProne.compile)
        assert(res.isLeft)
      }
    }
  }
}
