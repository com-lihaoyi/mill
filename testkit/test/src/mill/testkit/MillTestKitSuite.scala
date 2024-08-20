package mill.testkit

import mill._
import utest._

object MillTestKitSuite extends TestSuite {

  def tests: Tests = Tests {
    test("simple") {
      object build extends mill.testkit.BaseModule {
        def testTask = T { "test" }
      }

      val testEvaluator = new TestEvaluator(build)
      val result = testEvaluator(build.testTask).map(_._1)
      assert(result == Right("test"))
    }

    test("sources") {
      object build extends mill.testkit.BaseModule {
        def testSource = T.source(millSourcePath / "source-file.txt")
        def testTask = T { os.read(testSource().path).toUpperCase() }
      }

      os.copy.over(
        os.pwd / "testkit" / "test" / "resources" / "example-project",
        build.millSourcePath,
        createFolders = true
      )
      val testEvaluator = new TestEvaluator(build)
      val result = testEvaluator(build.testTask).map(_._1)
      assert(result == Right("HELLO WORLD SOURCE FILE"))
    }
  }

}
