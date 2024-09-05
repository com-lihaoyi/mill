package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object NonIdentifierImport extends IntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      assert(eval("foo-bar-module.compile").isSuccess)
    }
  }
}
