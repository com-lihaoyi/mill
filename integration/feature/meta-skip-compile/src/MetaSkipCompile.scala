package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object MetaSkipCompile extends UtestIntegrationTestSuite {

  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      // Make sure that when we pass in --meta-level 1, we stop after
      // evaluating that level of the meta-build and do not proceed further,
      // such that compilation errors in the build.mill do not affect us
      val result1 = tester.eval(("--meta-level", "1", "resolve", "_"))
      assert(result1.isSuccess)

      // Without --meta-level 1, we hit the compilation error
      val result2 = tester.eval(("resolve", "_"))
      assert(!result2.isSuccess)

      // Remove compilation error makes non-meta-level commands work again
      os.write.over(tester.workspacePath / "build.mill", "")
      val result3 = tester.eval(("resolve", "_"))
      assert(result3.isSuccess)
    }
  }
}
