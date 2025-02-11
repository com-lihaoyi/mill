package mill.javalib.checkstyle

import mill.*
import mainargs.Leftover
import mill.define.Discover
import mill.scalalib.{JavaModule, ScalaModule}
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*

object CheckstyleXsltModuleTest extends TestSuite {

  def tests: Tests = Tests {

    val resources: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "checkstyle"

    test("checkstyle generates XSLT output reports") {

      assert(
        testJava(resources / "non-compatible"),
        testScala(resources / "compatible-scala")
      )
    }

    test("checkstyle succeeds when no XSLT files are found") {

      assert(
        testJava(resources / "compatible-java")
      )
    }
  }

  def testJava(modulePath: os.Path): Boolean = {

    object module extends TestBaseModule with JavaModule with CheckstyleXsltModule {
      lazy val millDiscover = Discover[this.type]
    }

    testModule(module, modulePath)
  }

  def testScala(modulePath: os.Path): Boolean = {

    object module extends TestBaseModule with ScalaModule with CheckstyleXsltModule {
      override def scalaVersion: T[String] = sys.props("MILL_SCALA_2_13_VERSION")
      lazy val millDiscover = Discover[this.type]
    }

    testModule(module, modulePath)
  }

  def testModule(module: TestBaseModule & CheckstyleXsltModule, modulePath: os.Path): Boolean = {
    val eval = UnitTester(module, modulePath)

    eval(module.checkstyle(CheckstyleArgs(check = false, sources = Leftover()))).fold(
      {
        case api.ExecResult.Exception(cause, _) => throw cause
        case failure => throw failure
      },
      _ => {

        val Right(reports) = eval(module.checkstyleXsltReports): @unchecked

        reports.value.forall(report => os.exists(report.output.path))
      }
    )
  }
}
