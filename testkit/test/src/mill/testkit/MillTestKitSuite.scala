package mill.testkit

import mill._
import utest._

object MillTestKitSuite extends TestSuite {

  object build extends MillTestKit.BaseModule {
    def testTask = T("test")
  }



  def tests: Tests = Tests {
    "Test evaluator allows to run tasks" - {
      val testEvaluator = new TestEvaluator(build)
      val result = testEvaluator(build.testTask).map(_._1)
      assert(result == Right("test"))
    }
  }

}
