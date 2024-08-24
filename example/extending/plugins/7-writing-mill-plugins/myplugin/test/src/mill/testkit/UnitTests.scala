package myplugin

import mill.testkit.{TestBaseModule, UnitTester}
import utest._

object UnitTests extends TestSuite {
  def tests: Tests = Tests {
    test("unit") {
      object build extends TestBaseModule with LineCountJavaModule{
        def lineCountResourceFileName = "line-count.txt"
      }

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))
      val eval = UnitTester(build, resourceFolder/ "unit-test-project")

      // Evaluating tasks by direct reference
      val Right(result) = eval(build.resources)
      assert(
        result.value.exists(pathref =>
          os.exists(pathref.path / "line-count.txt") &&
          os.read(pathref.path / "line-count.txt") == "17"
        )
      )

      // Evaluating tasks by passing in their Mill selector
      val Right(result2) = eval("resources")
      val Seq(pathrefs: Seq[mill.api.PathRef]) = result2.value
      assert(
        pathrefs.exists(pathref =>
          os.exists(pathref.path / "line-count.txt") &&
            os.read(pathref.path / "line-count.txt") == "17"
        )
      )
    }
  }
}
