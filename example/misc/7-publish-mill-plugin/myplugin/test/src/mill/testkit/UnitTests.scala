package myplugin

//import mill._
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

object UnitTests extends TestSuite {

  def tests: Tests = Tests {
    test("simple") {
      object build extends TestBaseModule with LineCountJavaModule

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))
      val eval = UnitTester(build, resourceFolder/ "unit-test-example-project")
      val Right(result) = eval(build.resources)
      assert(
        result.value.exists(pref =>
          os.exists(pref.path / "line-count.txt") &&
          os.read(pref.path / "line-count.txt") == "17"
        )
      )
    }
  }
}
