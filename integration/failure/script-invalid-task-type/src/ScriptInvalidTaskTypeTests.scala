package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ScriptInvalidTaskTypeTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("./Foo.java")
      assert(res.err.contains(
        "Foo.java.mvnDeps Failed de-serializing config override: expected sequence got string"
      ))
      // make sure we truncate the exception to the relevant bits
      assert(res.err.linesIterator.toList.length < 20)
    }
  }
}
