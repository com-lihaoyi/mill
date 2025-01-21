package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object LargeProjectTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._

      assert(eval("foo.common.one.compile").isSuccess)
    }
  }
}
