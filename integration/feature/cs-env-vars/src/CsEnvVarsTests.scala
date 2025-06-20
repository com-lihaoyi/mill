package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

import java.io.File

object CsEnvVarsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("env vars") - integrationTest { tester =>
      import tester._

      def check(cache: os.Path): Unit = {
        val res = eval(
          ("printRunClasspath"),
          env = Map("COURSIER_CACHE" -> cache.toString)
        )
        assert(res.exitCode == 0)

        pprint.err.log(res.out)
        val cp = res.out.split(File.pathSeparator).map(os.Path(_))
        assert(cp.exists(p => p.startsWith(cache) && p.last.startsWith("slf4j-api-")))
      }

      check(workspacePath / "cache-0")
      check(workspacePath / "cache-1")
      check(workspacePath / "cache-2")
    }
  }
}
