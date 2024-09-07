package mill
package contrib.checkstyle

import mill.scalalib.JavaModule
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object CheckstyleXsltModuleTest extends TestSuite {

  def tests: Tests = Tests {
    test("CheckstyleXsltModule") {
      test("sbt-checkstyle-xslt") {
        xslt.exists(os.rel / "sbt" / "checkstyle-xslt", "10.18.1")
      }
    }
  }
}

private object xslt {

  def exists(module: os.RelPath, version: String): Boolean = {
    object mod extends TestBaseModule with JavaModule with CheckstyleXsltModule {
      override def checkstyleVersion: T[String] = version
    }
    val root = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / module
    val eval = UnitTester(mod, root)
    val Right(src) = eval(mod.checkstyle)
    val Right(targets) = eval(mod.checkstyleXsltTargets)
    val dst = src.value.path / os.up
    targets.value.forall {
      case (_, out) => os.exists(dst / out)
    }
  }
}
