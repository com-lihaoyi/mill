package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object NestedBuildWithoutFlagTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("nestedBuildWithoutFlag") - integrationTest { tester =>
      val res = tester.eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains(
        """Package declaration "package build" in deps/foo/build.mill does not match folder structure. Expected: "package build.deps.foo""""
      ))
      // Should contain the hint about the flag
      assert(res.err.contains("allowNestedBuildMillFiles: true"))
    }
  }
}
