package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

/**
 * Tests for the nonBootstrapped flag on tasks.
 *
 * When a task is marked as nonBootstrapped = true, it can be run even when the
 * root build.mill file has compile errors, as long as the meta-build level compiles.
 *
 * This is useful for commands like `version`, `shutdown`, `clean`, `init` that
 * don't need the full build to work.
 */
object NonBootstrappedTaskTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("builtin nonBootstrapped commands work with broken build.mill") - integrationTest {
      tester =>
        import tester.*

        // version should work even with broken build.mill
        val versionResult = eval("version")
        assert(versionResult.isSuccess)

        // clean with no args should work
        val cleanResult = eval("clean")
        assert(cleanResult.isSuccess)
    }
  }
}
