package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object KeywordModuleTest extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._

      assert(eval("import.task").isSuccess)
    }
  }
}
