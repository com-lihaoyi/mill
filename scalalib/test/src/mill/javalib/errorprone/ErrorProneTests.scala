package mill.javalib.errorprone

import mill.T
import mill.scalalib.JavaModule
import mill.testkit.{TestBaseModule, UnitTester}
import os.Path
import utest._

object ErrorProneTests extends TestSuite {

  object noErrorProne extends TestBaseModule with JavaModule {}
  object errorProne extends TestBaseModule with JavaModule with ErrorProneModule {}
  object errorProneCustom extends TestBaseModule with JavaModule with ErrorProneModule {
    override def errorProneOptions: T[Seq[String]] = T(Seq(
      "-XepAllErrorsAsWarnings"
    ))
  }

  val testModuleSourcesPath: Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "errorprone"

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
      test("compileWarn") {
        val eval = UnitTester(errorProneCustom, testModuleSourcesPath, debugEnabled = true)
        val Right(opts) = eval(errorProneCustom.mandatoryJavacOptions)
        assert(opts.value.exists(_.contains("-XepAllErrorsAsWarnings")))
        val res = eval(errorProneCustom.compile)
        assert(res.isRight)
      }
    }
  }
}
