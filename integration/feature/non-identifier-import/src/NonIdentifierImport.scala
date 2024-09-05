package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object NonIdentifierImport extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      assert(eval("foo-bar-module.compile").isSuccess)
    }
  }
}
