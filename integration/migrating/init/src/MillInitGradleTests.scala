package mill.integration

import mill.integration.IntegrationTesterUtil.renderAllImportedConfigurations
import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitGradleTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test("FastCSV") - integrationTestGitRepo(
      "https://github.com/osiegmar/FastCSV.git",
      "v4.0.0"
    ) { tester =>
      import tester.{eval, workspaceSourcePath as resources}

      val initRes = eval(("init", "--gradle-jvm-id", "24"))
      assert(initRes.isSuccess)

      val configurations = renderAllImportedConfigurations(tester)
      assertGoldenFile(
        configurations,
        (resources / "golden/gradle/fast-csv").wrapped
      )

      val compileRes = tester.eval("lib.compile")
      assert(
        !compileRes.isSuccess,
        compileRes.err.contains("warnings found and -Werror specified")
      )
    }

    test("ehcache3") - integrationTestGitRepo(
      "https://github.com/ehcache/ehcache3.git",
      "v3.10.8"
    ) { tester =>
      import tester.{eval, workspaceSourcePath as resources}

      val initRes = eval(("init", "--gradle-jvm-id", "11"))
      assert(initRes.isSuccess)

      val configurations = renderAllImportedConfigurations(tester)
      assertGoldenFile(
        configurations,
        (resources / "golden/gradle/ehcache3").wrapped
      )

      val compileRes = eval("ehcache-api.compile")
      assert(
        !compileRes.isSuccess,
        compileRes.err.contains("warnings found and -Werror specified")
      )
    }
  }
}
