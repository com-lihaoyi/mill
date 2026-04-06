package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object LargeProjectTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester.*

      assert(eval("foo.common.one.compile").isSuccess)
    }
  }
}
