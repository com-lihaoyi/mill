package mill.testkit

import mill._
import utest._

object UnitTestTests extends TestSuite {

  def tests: Tests = Tests {
    test("simple") {
      object build extends TestBaseModule {
        def testTask = T { "test" }
      }

      val eval = UnitTester(build)
      val Right(result) = eval(build.testTask)
      assert(result.value == "test")
    }

    test("sources") {
      object build extends TestBaseModule {
        def testSource = T.source(millSourcePath / "source-file.txt")
        def testTask = T { os.read(testSource().path).toUpperCase() }
      }

      val eval = UnitTester(
        build,
        sourceRoot = os.pwd / "testkit" / "test" / "resources" / "unit-test-example-project"
      )

      val Right(result) = eval(build.testTask)
      assert(result.value == "HELLO WORLD SOURCE FILE")
    }
  }
}
