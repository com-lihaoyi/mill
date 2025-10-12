package mill.integration

import mill.integration.IntegrationTesterUtil.renderAllImportedConfigurations
import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitSbtTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test("airstream") - integrationTestGitRepo(
      "https://github.com/raquo/Airstream.git",
      "v17.2.1"
    ) { tester =>
      import tester.{eval, workspaceSourcePath as resources}

      val initRes = eval("init")
      assert(initRes.isSuccess)

      val configurations = renderAllImportedConfigurations(tester)
      assertGoldenFile(
        configurations,
        (resources / "golden/sbt/airstream").wrapped
      )

      val compileRes = eval("[3.3.3].compile")
      assert(
        compileRes.isSuccess,
        compileRes.err.contains("compiling 144 Scala sources")
      )
    }
    test("fs2") - integrationTestGitRepo(
      "https://github.com/typelevel/fs2.git",
      "v3.12.0"
    ) {
      tester =>
        import tester.{eval, workspaceSourcePath as resources}

        val initRes = eval("init")
        assert(initRes.isSuccess)

        val configurations = renderAllImportedConfigurations(tester)
        assertGoldenFile(
          configurations,
          (resources / "golden/sbt/fs2").wrapped
        )

        val testOnlyJvmRes = eval(("core.jvm[2.13.16].test.testOnly", "fs2.hashing.HashingSuite"))
        assert(
          testOnlyJvmRes.isSuccess,
          testOnlyJvmRes.err.contains("Running Test Class fs2.hashing.HashingSuite")
        )
        val testOnlyNativeRes =
          eval(("core.native[2.12.20].test.testOnly", "fs2.hashing.HashingSuite"))
        assert(
          // com.lihaoyi:mill-libs-scalanativelib-worker-0.4_3:SNAPSHOT
          !testOnlyNativeRes.isSuccess,
          testOnlyNativeRes.err.contains("scalaNativeWorkerClasspath java.lang.RuntimeException")
        )
    }
  }
}
