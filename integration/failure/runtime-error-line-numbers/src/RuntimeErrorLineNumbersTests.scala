package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object RuntimeErrorLineNumbersTests extends UtestIntegrationTestSuite {
  def captureOutErr = true
  val tests: Tests = Tests {
    test("resolve") - integrationTest { tester =>
      val res1 = tester.eval("rootBoom")
      pprint.log(res1.err)
      assert(!res1.isSuccess)
      val res2 = tester.eval("rootUtilBoom")
      pprint.log(res2.err)
      assert(!res2.isSuccess)
      val res3 = tester.eval("fooBoom")
      pprint.log(res3.err)
      assert(!res3.isSuccess)
      val res4 = tester.eval("fooVersionsBoom")
      pprint.log(res4.err)
      assert(!res4.isSuccess)
    }
  }
}
