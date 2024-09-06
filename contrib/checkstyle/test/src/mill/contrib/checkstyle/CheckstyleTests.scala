package mill.contrib.checkstyle

import com.etsy.sbt.checkstyle.{CheckstyleConfigLocation, CheckstyleSeverityLevel}
import com.etsy.sbt.checkstyle.CheckstyleSeverityLevel.CheckstyleSeverityLevel
import mill.{Agg, T}
import mill.scalalib.{Dep, DepSyntax, JavaModule}
import mill.testkit.{TestBaseModule, UnitTester}
import os.Path
import utest._

object CheckstyleTests extends TestSuite {

  object checkstyle extends TestBaseModule with JavaModule with CheckstyleModule {
    override def checkstyleVersion: T[String] = "9.3"
  }
  object checkstyleFatal extends TestBaseModule with JavaModule with CheckstyleModule {
    override def checkstyleSeverityLevel: Option[CheckstyleSeverityLevel] =
      Some(CheckstyleSeverityLevel.Info)

    override def checkstyleVersion: T[String] = "9.3"
  }
  object checkstyleClasspathConfig extends TestBaseModule with JavaModule with CheckstyleModule {
    override def checkstyleVersion: T[String] = "9.3"

    override def ivyDeps: T[Agg[Dep]] = Agg(
      ivy"com.puppycrawl.tools:checkstyle:${checkstyleVersion()}"
    )

    override def checkstyleConfigLocation: T[CheckstyleConfigLocation] =
      T.input { CheckstyleConfigLocation.Classpath("google_checks.xml") }
  }

  val testModuleSourcesPath: Path = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "checkstyle"

  def tests = Tests {
    test("checkstyle") {
      test("report") {
        val eval = UnitTester(checkstyle, testModuleSourcesPath, debugEnabled = true)
        val res = eval(checkstyle.checkstyle)
        assert(res.isRight)
        val expectedReport = eval.outPath / "checkstyle.dest" / "checkstyle-report.xml"
        assert(expectedReport.toIO.isFile)
      }
      test("fatal") {
        val eval = UnitTester(checkstyleFatal, testModuleSourcesPath)
        val res = eval(checkstyleFatal.checkstyle)
        assert(res.isLeft)
      }
      test("configfromclasspath") {
        val eval = UnitTester(checkstyleClasspathConfig, testModuleSourcesPath, debugEnabled = true)
        val res = eval(checkstyleClasspathConfig.checkstyle)
        assert(res.isRight)
        val expectedConfig = eval.outPath / "checkstyle.dest" / "checkstyle-config.xml"
        assert(expectedConfig.toIO.isFile)
      }
    }
  }
}
