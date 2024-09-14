package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ThingsOutsideTopLevelModuleTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester._
      val res = eval(("resolve", "_"))
      assert(res.isSuccess)
    }
  }
}
