package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ScriptInvalidModuleDepTypeTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("./Foo.java")
      assert(res.err.contains(
        "Failed de-serializing config key $['moduleDeps'] in Foo.java: expected sequence got string"
      ))
      assert(res.err.linesIterator.toList.length < 20)
    }
  }
}
