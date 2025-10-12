package mill.integration

import mill.integration.IntegrationTesterUtil.renderAllImportedConfigurations
import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitMavenTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test("jansi") - integrationTestGitRepo(
      "https://github.com/fusesource/jansi.git",
      "jansi-2.4.2"
    ) { tester =>
      import tester.{eval, workspaceSourcePath as resources}

      val initRes = eval("init")
      assert(initRes.isSuccess)

      val configurations = renderAllImportedConfigurations(tester)
      assertGoldenFile(
        configurations,
        (resources / "golden/maven/jansi").wrapped
      )

      val compileRes = tester.eval("compile")
      assert(
        compileRes.isSuccess,
        compileRes.err.contains("compiling 20 Java sources")
      )
    }

    test("netty") - integrationTestGitRepo(
      "https://github.com/netty/netty.git",
      "netty-4.2.6.Final"
    ) { tester =>
      import tester.{eval, workspaceSourcePath as resources}

      val initRes = eval("init")
      assert(initRes.isSuccess)

      val configurations = renderAllImportedConfigurations(tester)
      assertGoldenFile(
        configurations,
        (resources / "golden/maven/netty").wrapped
      )

      val commonCompileRes = eval("common.compile")
      assert(
        commonCompileRes.isSuccess,
        commonCompileRes.err.contains("compiling 196 Java sources")
      )
    }
  }
}
