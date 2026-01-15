package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

/**
 * Tests for the @nonBootstrapped annotation on tasks.
 *
 * When a task is marked with @nonBootstrapped, it can be run even when the
 * root build.mill file has compile errors, as long as the meta-build level compiles.
 *
 * This is useful for commands like `version`, `shutdown`, `clean`, `init` that
 * don't need the full build to work.
 */
object NonBootstrappedTaskTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("builtins") - integrationTest {
      tester =>
        import tester.*

        // version should work even with broken build.mill
        val versionResult = eval("version")
        assert(versionResult.isSuccess)
        
        val shutdownResult = eval("shutdown")
        assert(shutdownResult.isSuccess)
    }

    test("custom") - integrationTest {
      tester =>
        import tester.*

        // Custom @nonBootstrapped command defined in mill-build/build.mill should work
        val customResult = eval("mill.build/myNonBootstrappedTask")
        assert(customResult.isSuccess)
        assert(customResult.out.contains("Running myNonBootstrappedTask"))
    }

    test("regularFails") - integrationTest { tester =>
      import tester.*

      // Regular command (not @nonBootstrapped) should fail because build.mill has errors
      val regularResult = eval("mill.build/myRegularTask")
      assert(!regularResult.isSuccess)
      // The error should mention the build.mill error
      assert(regularResult.err.contains("boom"))
    }
  }
}
