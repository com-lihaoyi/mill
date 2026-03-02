package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object TestPackageConflictTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("foo")
      // Ensuring the position of the error we get is in build.mill
      // rather than in its corresponding generated source file
      assert(!res.err.contains("generatedScriptSources.dest"))
      assert(res.err.contains("[error] build.mill:"))
    }
  }
}
