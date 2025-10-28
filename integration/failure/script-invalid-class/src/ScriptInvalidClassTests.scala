package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ScriptInvalidClassTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("./Foo.java")
      assert(res.err.contains("Script Foo.java extends invalid class \"doesntExist\""))
      // make sure we truncate the exception to the relevant bits
      assert(res.err.linesIterator.toList.length < 20)
    }
  }
}
