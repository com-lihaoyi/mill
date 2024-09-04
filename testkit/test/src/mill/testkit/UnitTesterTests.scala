package mill.testkit

import mill._
import utest._

object UnitTesterTests extends TestSuite {

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "unit-test-example-project"
  def tests: Tests = Tests {
    test("simple") {
      object build extends TestBaseModule {
        def testTask = T { "test" }
      }

      val eval = UnitTester(build, resourcePath)
      val Right(result) = eval(build.testTask)
      assert(result.value == "test")
    }

    test("sources") {
      object build extends TestBaseModule {
        def testSource = T.source(millSourcePath / "source-file.txt")
        def testTask = T { os.read(testSource().path).toUpperCase() }
      }

      val eval = UnitTester(build, resourcePath)

      val Right(result) = eval(build.testTask)
      assert(result.value == "HELLO WORLD SOURCE FILE")
    }
  }
}
