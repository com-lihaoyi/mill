package mill.javalib.checkstyle

import mill.*
import mainargs.Leftover
import mill.api.Discover
import mill.javalib.JavaModule
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object CheckstyleXsltModuleTest extends TestSuite {

  def tests: Tests = Tests {

    val resources: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "checkstyle"

    test("checkstyle generates XSLT output reports") {

      assert(testJava(resources / "non-compatible"))
    }

    test("checkstyle succeeds when no XSLT files are found") {

      assert(testJava(resources / "compatible-java"))
    }
  }

  def testJava(modulePath: os.Path): Boolean = {

    object module extends TestRootModule with JavaModule with CheckstyleXsltModule {
      lazy val millDiscover = Discover[this.type]
    }

    testModule(module, modulePath)
  }

  def testModule(module: TestRootModule & CheckstyleXsltModule, modulePath: os.Path): Boolean = {
    UnitTester(module, modulePath).scoped { eval =>
      eval(module.checkstyle(CheckstyleArgs(check = false, sources = Leftover()))).get

      val Right(reports) = eval(module.checkstyleXsltReports): @unchecked

      reports.value.forall(report => os.exists(report.output.path))
    }
  }
}
