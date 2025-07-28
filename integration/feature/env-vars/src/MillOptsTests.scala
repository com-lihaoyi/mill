package mill.integration

import mill.constants.Util
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*

object MillOptsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {

    test("envVars") - integrationTest { tester =>
      // Make sure environment  variables passed to the launcher process
      // are passed to the `run` and `test` subprocess, and are up-to-date
      // even when the env var changes despite the Mill process being the same
      val res1 = tester.eval("foo.run", env = Map("MY_ENV_VAR" -> "12345"))
      assert(res1.out.contains("run: 12345"))

      val res2 = tester.eval("foo.run", env = Map("MY_ENV_VAR" -> "56789"))
      assert(res2.out.contains("run: 56789"))

      val res3 = tester.eval("foo.test", env = Map("MY_ENV_VAR" -> "abcde"))
      assert(res3.out.contains("test: abcde"))
      assert(res3.isSuccess)

      val res4 = tester.eval("foo.test", env = Map("MY_ENV_VAR" -> "efghi"))
      assert(res4.out.contains("test: efghi"))
      assert(!res4.isSuccess)


      // If `def propagateEnv = false` is configured, no env vars are passed
      // from the launcher to the daemon.
      tester.modifyFile(tester.workspacePath / "build.mill", _.replace("}", "def propagateEnv = false}"))

      val res5 = tester.eval("foo.run", env = Map("MY_ENV_VAR" -> "12345"))
      assert(res5.out.contains("run: null"))

      val res6 = tester.eval("foo.run", env = Map("MY_ENV_VAR" -> "56789"))
      assert(res6.out.contains("run: null"))

      val res7 = tester.eval("foo.test", env = Map("MY_ENV_VAR" -> "abcde"))
      assert(res7.out.contains("test: null"))
      assert(!res7.isSuccess)

      val res8 = tester.eval("foo.test", env = Map("MY_ENV_VAR" -> "efghi"))
      assert(res8.out.contains("test: null"))
      assert(!res8.isSuccess)
    }
  }
}
