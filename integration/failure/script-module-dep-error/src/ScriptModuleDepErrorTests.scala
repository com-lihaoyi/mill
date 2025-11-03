package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ScriptModuleDepErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("./Foo.java")
      assert(res.err.contains("Unable to resolve modules: \"doesntExist\""))
    }
  }
}
