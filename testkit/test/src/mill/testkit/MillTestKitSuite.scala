package mill.testkit

import mill.testkit.MillTestKit
import mill._
import utest._

object MillTestKitSuite extends TestSuite {

  object testKit extends MillTestKit

  val testEvaluator = testKit.staticTestEvaluator(build)

  object build extends testKit.BaseModule {
    def testTask = T("test")
  }

  def tests: Tests = Tests {
    "Test evaluator allows to run tasks" - {
      val result = testEvaluator(build.testTask).map(_._1)
      assert(result == Right("test"))
    }
  }

}
