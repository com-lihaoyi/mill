package mill.bsp

import mill.util.{TestEvaluator, TestUtil}
import utest.{TestSuite, test, Tests}

object ModuleUtilsTests extends TestSuite {
  object TestModule extends TestUtil.BaseModule

  val testEvaluator = TestEvaluator.static(TestModule)

  def tests: Tests = Tests {
    test("getMillBuildClasspath should return URI Path") {
      test("sources is false") {
        assert(ModuleUtils.getMillBuildClasspath(testEvaluator.evaluator, false).forall(_.startsWith("file://")))
      }
      test("sources is true") {
        assert(ModuleUtils.getMillBuildClasspath(testEvaluator.evaluator, true).forall(_.startsWith("file://")))
      }
    }
  }
}
