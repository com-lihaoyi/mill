package mill.testkit

import mill._
import mill.testkit.MillTestKit.TestEvaluator
import utest._

object MillTestKitSuite extends TestSuite {

  object build extends MillTestKit.BaseModule {
    def testTask = T("test")
  }

  val testEvaluator = new TestEvaluator(build, Seq.empty)
  
  def tests: Tests = Tests {
    "Test evaluator allows to run tasks" - {
      val result = testEvaluator(build.testTask).map(_._1)
      assert(result == Right("test"))
    }
  }

}
