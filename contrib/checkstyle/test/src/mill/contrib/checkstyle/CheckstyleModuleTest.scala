package mill
package contrib.checkstyle

import mill.scalalib.JavaModule
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object CheckstyleModuleTest extends TestSuite {

  import CheckstyleModule.Format
  import CheckstyleModule.Format._

  private def compatibility(vlo: String, vhi: String, format: Format, module: String): Unit = {
    intercept[RuntimeException](generate(vlo, format, module))
    assert(generate(vhi, format, module))
  }

  private object generate {

    def apply(version: String, module: String): Boolean = {
      Format.values.forall(apply(version, _, module))
    }

    def apply(version: String, format: Format, module: String): Boolean = {
      object mod extends TestBaseModule with JavaModule with CheckstyleModule {
        override def checkstyleVersion = version
        override def checkstyleFormat = format
      }
      val eval = UnitTester(mod, root / module)
      val Right(result) = eval(mod.checkstyle)
      os.exists(result.value.path)
    }

    private val root = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))
  }

  def tests = Tests {
    test("checkstyle") {
      test("compatibility") {
        test("plain") {
          compatibility("6.2", "6.3", plain, "sbt-checkstyle")
        }
        test("sarif") {
          compatibility("8.42", "8.43", sarif, "sbt-checkstyle")
        }
        test("xml") {
          compatibility("6.2", "6.3", xml, "sbt-checkstyle")
        }
      }
      test("v10.18.1") {
        assert(generate("10.18.1", "sbt-checkstyle"))
      }
    }
  }
}
