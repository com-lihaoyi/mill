package mill.contrib.checkstyle

import com.etsy.sbt.checkstyle.CheckstyleSeverityLevel
import com.etsy.sbt.checkstyle.CheckstyleSeverityLevel.CheckstyleSeverityLevel
import mill.scalalib.JavaModule
import mill.testkit.{TestBaseModule, UnitTester}
import os.Path
import utest._

object CheckstyleTests extends TestSuite {

  object noCheckstyle extends TestBaseModule with JavaModule {}
  object checkstyle extends TestBaseModule with JavaModule with CheckstyleModule {}
  object checkstyleCustom extends TestBaseModule with JavaModule with CheckstyleModule {
    override def checkstyleSeverityLevel: Option[CheckstyleSeverityLevel] =
      Some(CheckstyleSeverityLevel.Info)
  }

  val testModuleSourcesPath: Path = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "simple"

  def tests = Tests {
    test("reference") {
      test("compile") {
        val eval = UnitTester(noCheckstyle, testModuleSourcesPath)
        val res = eval(noCheckstyle.compile)
        assert(res.isRight)
      }
    }
    test("checkstyle") {
      test("compileFail") {
        val eval = UnitTester(checkstyle, testModuleSourcesPath)
        val res = eval(checkstyle.compile)
        assert(res.isLeft)
      }
      test("compileWarn") {
        val eval = UnitTester(checkstyleCustom, testModuleSourcesPath, debugEnabled = true)
        val Right(opts) = eval(checkstyleCustom.javacOptions)
        assert(opts.value.exists(_.contains("-XepAllErrorsAsWarnings")))
        val res = eval(checkstyleCustom.compile)
        assert(res.isRight)
      }
    }
  }
}
