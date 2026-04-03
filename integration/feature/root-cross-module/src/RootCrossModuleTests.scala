package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object RootCrossModuleTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("root") - integrationTest {
      tester =>
        import tester.*
        assert(eval("[2.13.16].foo").isSuccess)
    }

    test("module") - integrationTest {
      tester =>
        import tester.*
        assert(eval("baz[2.13.16].foo").isSuccess)
    }

    test("subpackage") - integrationTest {
      tester =>
        import tester.*
        assert(eval("bar[2.13.16].foo").isSuccess)
    }
  }
}
