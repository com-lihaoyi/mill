package mill.integration

import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

object MillInitImportGradleTests extends GitRepoIntegrationTestSuite {
  def tests = Tests {
    test("FastCSV") - integrationTestGitRepo(
      "https://github.com/osiegmar/FastCSV.git",
      "v4.0.0"
    ) { tester =>
      import tester.{eval, workspaceSourcePath as resources}

      val initRes = eval(("init", "--gradle-jvm-id", "24"))
      assert(initRes.isSuccess)

      val compileRes = tester.eval("lib.compile")
      assert(
        // Gradle autoconfigures javac option -proc:none when -processorpath is not provided
        !compileRes.isSuccess,
        compileRes.err.contains("warnings found and -Werror specified")
      )

      val showModuleDeps = eval("__.showModuleDeps").out
      val repositories = eval(("show", "__.repositories")).out
      val jvmId = eval(("show", "__.jvmId")).out
      val mvnDeps = eval(("show", "__.mvnDeps")).out
      val compileMvnDeps = eval(("show", "__.compileMvnDeps")).out
      val runMvnDeps = eval(("show", "__.runMvnDeps")).out
      val bomMvnDeps = eval(("show", "__.bomMvnDeps")).out
      val javacOptions = eval(("show", "__.javacOptions")).out
      val pomParentProject = eval(("show", "__.pomParentProject")).out
      val pomSettings = eval(("show", "__.pomSettings")).out
      val publishVersion = eval(("show", "__.publishVersion")).out
      val versionScheme = eval(("show", "__.versionScheme")).out
      eval(("show", "__.publishProperties"))
      val errorProneVersion = eval(("show", "__.errorProneVersion"))
      val errorProneDeps = eval(("show", "__.errorProneDeps"))
      val errorProneJavacEnableOptions = eval(("show", "errorProneJavacEnableOptions"))
      val errorProneOptions = eval(("show", "__.errorProneOptions"))
      assertGoldenFile(
        s"""$showModuleDeps
           |$repositories
           |$jvmId
           |$mvnDeps
           |$compileMvnDeps
           |$runMvnDeps
           |$bomMvnDeps
           |$javacOptions
           |$pomParentProject
           |$pomSettings
           |$publishVersion
           |$versionScheme
           |$errorProneVersion
           |$errorProneDeps
           |$errorProneJavacEnableOptions
           |$errorProneOptions
           |""".stripMargin,
        (resources / "golden/gradle/fast-csv").wrapped
      )
    }

    test("spring-framework") - integrationTestGitRepo(
      "https://github.com/spring-projects/spring-framework.git",
      "v6.2.11"
    ) { tester =>
      import tester.{eval, workspaceSourcePath as resources}

      val initRes = eval(("init", "--gradle-jvm-id", "17"))
      assert(initRes.isSuccess)

      val compileRes = eval("spring-core.compile")
      assert(
        // Gradle autoconfigures javac option -proc:none when -processorpath is not set
        !compileRes.isSuccess,
        compileRes.err.contains("spring-core.resolvedMvnDeps java.lang.RuntimeException")
      )

      val showModuleDeps = eval("__.showModuleDeps").out
      val repositories = eval(("show", "__.repositories")).out
      val jvmId = eval(("show", "__.jvmId")).out
      val mvnDeps = eval(("show", "__.mvnDeps")).out
      val compileMvnDeps = eval(("show", "__.compileMvnDeps")).out
      val runMvnDeps = eval(("show", "__.runMvnDeps")).out
      val bomMvnDeps = eval(("show", "__.bomMvnDeps")).out
      val javacOptions = eval(("show", "__.javacOptions")).out
      val pomParentProject = eval(("show", "__.pomParentProject")).out
      val pomSettings = eval(("show", "__.pomSettings")).out
      val publishVersion = eval(("show", "__.publishVersion")).out
      val versionScheme = eval(("show", "__.versionScheme")).out
      eval(("show", "__.publishProperties"))
      val errorProneVersion = eval(("show", "__.errorProneVersion"))
      val errorProneDeps = eval(("show", "__.errorProneDeps"))
      val errorProneJavacEnableOptions = eval(("show", "errorProneJavacEnableOptions"))
      val errorProneOptions = eval(("show", "__.errorProneOptions"))
      assertGoldenFile(
        s"""$showModuleDeps
           |$repositories
           |$jvmId
           |$mvnDeps
           |$compileMvnDeps
           |$runMvnDeps
           |$bomMvnDeps
           |$javacOptions
           |$pomParentProject
           |$pomSettings
           |$publishVersion
           |$versionScheme
           |$errorProneVersion
           |$errorProneDeps
           |$errorProneJavacEnableOptions
           |$errorProneOptions
           |""".stripMargin,
        (resources / "golden/gradle/ehcache3").wrapped
      )
    }
  }
}
