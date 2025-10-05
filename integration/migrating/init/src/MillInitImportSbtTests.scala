package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitImportSbtTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test("airstream") - integrationTestGitRepo(
      "https://github.com/raquo/Airstream.git",
      "v17.2.1"
    ) { tester =>
      import tester.{eval, workspaceSourcePath as resources}

      val initRes = eval("init")
      assert(initRes.isSuccess)

      val compileRes = eval("[3.3.3].compile")
      assert(
        compileRes.isSuccess,
        compileRes.err.contains("compiling 144 Scala sources")
      )
      val testOnlyRes1 =
        eval(("[2.13.16].test.testOnly", "com.raquo.airstream.core.EventStreamSpec"))
      assert(
        testOnlyRes1.isSuccess,
        testOnlyRes1.out.contains("Test result: 1 completed")
      )
      val testOnlyRes2 =
        eval(("[2.13.16].test.testOnly", "com.raquo.airstream.web.WebStorageVarSpec"))
      assert(
        // requires support for mapping ScalaJSModule.jsEnvConfig
        !testOnlyRes2.isSuccess,
        testOnlyRes2.out.contains(
          "scala.scalajs.js.JavaScriptException: ReferenceError: window is not defined"
        )
      )

      val showModuleDeps = eval("__.showModuleDeps").out
      val repositories = eval(("show", "__.repositories")).out
      val jvmId = eval(("show", "__.jvmId")).out
      val mvnDeps = eval(("show", "__.mvnDeps")).out
      val compileMvnDeps = eval(("show", "__.compileMvnDeps")).out
      val runMvnDeps = eval(("show", "__.runMvnDeps")).out
      val bomMvnDeps = eval(("show", "__.bomMvnDeps")).out
      val javacOptions = eval(("show", "__.javacOptions")).out
      val pomParentPoject = eval(("show", "__.pomParentPoject")).out
      val pomSettings = eval(("show", "__.pomSettings")).out
      val publishVersion = eval(("show", "__.publishVersion")).out
      val versionScheme = eval(("show", "__.versionScheme")).out
      val scalaVersion = eval(("show", "__.scalaVersion")).out
      val scalacOptions = eval(("show", "__.scalacOptions")).out
      val scalacPluginMvnDeps = eval(("show", "__.scalacPluginMvnDeps")).out
      val scalaJSVersion = eval(("show", "__.scalaJSVersion")).out
      val moduleKind = eval(("show", "__.moduleKind")).out
      val scalaNativeVersion = eval(("show", "__.scalaNativeVersion")).out
      assertGoldenFile(
        s"""$showModuleDeps
           |$repositories
           |$jvmId
           |$mvnDeps
           |$compileMvnDeps
           |$runMvnDeps
           |$bomMvnDeps
           |$javacOptions
           |$pomParentPoject
           |$pomSettings
           |$publishVersion
           |$versionScheme
           |$scalaVersion
           |$scalacOptions
           |$scalacPluginMvnDeps
           |$scalaJSVersion
           |$moduleKind
           |$scalaNativeVersion
           |""".stripMargin,
        (resources / "golden/sbt/airstream").wrapped
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

        val testOnlyJvmRes = eval(("core.jvm[2.13.16].test.testOnly", "fs2.hashing.HashingSuite"))
        assert(
          testOnlyJvmRes.isSuccess,
          testOnlyJvmRes.err.contains("Running Test Class fs2.hashing.HashingSuite")
        )
        val testOnlyNativeRes =
          eval(("core.native[3.3.5].test.testOnly", "fs2.hashing.HashingSuite"))
        assert(
          // missing dependency for scalaNativeWorkerVersion
          !testOnlyNativeRes.isSuccess,
          testOnlyNativeRes.err.contains("scalaNativeWorkerClasspath java.lang.RuntimeException")
        )

        val showModuleDeps = eval("__.showModuleDeps").out
        val repositories = eval(("show", "__.repositories")).out
        val jvmId = eval(("show", "__.jvmId")).out
        val mvnDeps = eval(("show", "__.mvnDeps")).out
        val compileMvnDeps = eval(("show", "__.compileMvnDeps")).out
        val runMvnDeps = eval(("show", "__.runMvnDeps")).out
        val bomMvnDeps = eval(("show", "__.bomMvnDeps")).out
        val javacOptions = eval(("show", "__.javacOptions")).out
        val pomParentPoject = eval(("show", "__.pomParentPoject")).out
        val pomSettings = eval(("show", "__.pomSettings")).out
        val publishVersion = eval(("show", "__.publishVersion")).out
        val versionScheme = eval(("show", "__.versionScheme")).out
        val scalaVersion = eval(("show", "__.scalaVersion")).out
        val scalacOptions = eval(("show", "__.scalacOptions")).out
        val scalacPluginMvnDeps = eval(("show", "__.scalacPluginMvnDeps")).out
        val scalaJSVersion = eval(("show", "__.scalaJSVersion")).out
        val moduleKind = eval(("show", "__.moduleKind")).out
        val scalaNativeVersion = eval(("show", "__.scalaNativeVersion")).out
        assertGoldenFile(
          s"""$showModuleDeps
             |$repositories
             |$jvmId
             |$mvnDeps
             |$compileMvnDeps
             |$runMvnDeps
             |$bomMvnDeps
             |$javacOptions
             |$pomParentPoject
             |$pomSettings
             |$publishVersion
             |$versionScheme
             |$scalaVersion
             |$scalacOptions
             |$scalacPluginMvnDeps
             |$scalaJSVersion
             |$moduleKind
             |$scalaNativeVersion
             |""".stripMargin,
          (resources / "golden/sbt/fs2").wrapped
        )
    }
  }
}
