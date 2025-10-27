package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ScriptInvalidKeyError extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("./Foo.java")
      res.err.contains("invalid build config does not override any task: \"moduleDep\"")
      // make sure we truncate the exception to the relevant bits
      assert(res.err.linesIterator.toList.length < 20)
    }
  }
}
