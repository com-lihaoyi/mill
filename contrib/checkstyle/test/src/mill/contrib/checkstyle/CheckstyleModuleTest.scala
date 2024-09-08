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
    val src = "sbt-checkstyle"

    test("checkstyle") {
      test("plain") {
        cs("plain", "6.2") - src
        cs("plain", "6.3") + src
        cs("plain") + src
      }
      test("sarif") {
        cs("sarif", "8.42") - src
        cs("sarif", "8.43") + src
        cs("sarif") + src
      }
      test("xml") {
        cs("xml", "6.2") - src
        cs("xml", "6.3") + src
        cs("xml") + src
        cs("xml") + "sbt-checkstyle-xslt"
      }
    }
  }

  case class cs(format: String, version: String = ver) {

    def -(src: String) = intercept[RuntimeException] {
      passed(src)
    }

    def +(src: String) = assert {
      passed(src)
    }

    def passed(src: String) = {

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
