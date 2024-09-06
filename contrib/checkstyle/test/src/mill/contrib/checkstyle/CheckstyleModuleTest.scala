package mill
package contrib.checkstyle

import mill.scalalib.JavaModule
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object CheckstyleModuleTest extends TestSuite {

  def tests = Tests {
    test("CheckstyleModule") {
      test("compat") {
        test("plain") {
          format.compat("plain", os.rel / "sbt" / "checkstyle")("6.2")("6.3", "10.18.1")
        }
        test("sarif") {
          format.compat("sarif", os.rel / "sbt" / "checkstyle")("8.42")("8.43", "10.18.1")
        }
        test("xml") {
          format.compat("xml", os.rel / "sbt" / "checkstyle")("6.2")("6.3", "10.18.1")
        }
      }
    }
  }
}

private object format {

  def compat(format: String, module: os.RelPath)(fail: String*)(pass: String*): Unit = {
    fail.foreach(version => intercept[RuntimeException](exists(format, module, version)))
    pass.foreach(version => assert(exists(format, module, version)))
  }

  def exists(format: String, module: os.RelPath, version: String): Boolean = {
    object mod extends TestBaseModule with JavaModule with CheckstyleModule {
      override def checkstyleVersion = version
      override def checkstyleFormat = format
    }
    val root = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / module
    val eval = UnitTester(mod, root)
    val Right(result) = eval(mod.checkstyle)
    os.exists(result.value.path)
  }
}
