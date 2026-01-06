package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object KeywordModuleTest extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester.*

      assert(eval("for.task").isSuccess)
      assert(eval("if.task").isSuccess)
      assert(eval("import.task").isSuccess)
      assert(eval("null.task").isSuccess)
      assert(eval("this.task").isSuccess)
    }
  }
}
