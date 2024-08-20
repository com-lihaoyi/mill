package mill.testkit

import mill._
import utest._

object MillTestKitTests extends TestSuite {

  def tests: Tests = Tests {
    test("simple") {
      object build extends mill.testkit.BaseModule {
        def testTask = T { "test" }
      }

      val testEvaluator = new TestEvaluator(build)
      val Right(result) = testEvaluator(build.testTask)
      assert(result.value == "test")
    }

    test("sources") {
      object build extends mill.testkit.BaseModule {
        def testSource = T.source(millSourcePath / "source-file.txt")
        def testTask = T { os.read(testSource().path).toUpperCase() }
      }

      val testEvaluator = new TestEvaluator(
        build,
        sourceFileRoot = os.pwd / "testkit" / "test" / "resources" / "example-project"
      )

      val Right(result) = testEvaluator(build.testTask)
      assert(result.value == "HELLO WORLD SOURCE FILE")
    }
  }
}
