package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object LargeProjectTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester => import tester._

      assert(eval("foo.common.one.compile").isSuccess)
    }
  }
}
