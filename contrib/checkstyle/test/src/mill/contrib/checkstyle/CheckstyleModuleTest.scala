package mill
package contrib.checkstyle

import mill.scalalib.JavaModule
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object CheckstyleModuleTest extends TestSuite {

  val res = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))
  val ver = "10.18.1"

  def tests = Tests {

    test("checkstyle") {

      test("plain") {

        test("compatibility") {
          cs("plain", "6.3") passes "compatible"
          cs("plain", "6.2") fails "compatible"
        }

        test("default") {
          cs("plain") passes "compatible"
          cs("plain") fails "sbt-checkstyle"
        }
      }

      test("sarif") {

        test("compatibility") {
          cs("sarif", "8.43") passes "compatible"
          cs("sarif", "8.42") fails "compatible"
        }

        test("default") {
          cs("sarif") passes "compatible"
          cs("sarif") fails "sbt-checkstyle"
        }
      }

      test("xml") {

        test("compatibility") {
          cs("xml", "6.3") passes "compatible"
          cs("xml", "6.2") fails "compatible"
        }

        test("default") {
          cs("xml") passes "compatible"
          cs("xml") fails "sbt-checkstyle"
          cs("xml") fails "sbt-checkstyle-xslt"
        }
      }
    }
  }

  case class cs(format: String, version: String = ver) {

    def fails(src: String) = intercept[RuntimeException] {
      succeeded(src)
    }

    def passes(src: String) = assert {
      succeeded(src)
    }

    def succeeded(src: String) = {

      object mod extends TestBaseModule with JavaModule with CheckstyleModule {
        override def checkstyleFormat = format
        override def checkstyleVersion = version
      }

      val eval = UnitTester(mod, res / src)

      val Right(output) = eval(mod.checkstyleOutput)
      val Right(transforms) = eval(mod.checkstyleTransforms)
      val Right(report) = eval(mod.checkstyleReport)
      val Right(transformations) = eval(mod.checkstyle)

      val reported = report.value.path.endsWith(os.rel / output.value)
      val transformed = transforms.value.forall {
        case (_, rel) => transformations.value.exists(_.path.endsWith(os.rel / rel))
      }

      reported & transformed
    }
  }
}
