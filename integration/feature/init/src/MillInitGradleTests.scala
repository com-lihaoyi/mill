package mill.integration

import mill.main.client.Util
import utest._

object MillInitGradleJCommanderTests extends BuildGenTestSuite {

  def tests: Tests = Tests {
    // - single module
    // - custom javacOptions
    // - TestNg
    // - Gradle 8.9
    val url = "https://github.com/cbeust/jcommander/archive/refs/tags/2.0.zip"

    test - integrationTest(url) { tester =>
      import tester._

      val init = ("init", "--base-module", "BaseModule", "--deps-object", "Deps")
      val initRes = eval(init, stdout = os.Inherit, stderr = os.Inherit)
      assert(initRes.isSuccess)

      val compileRes = eval("compile")
      assert(compileRes.isSuccess)

      val testCompileRes = eval("test.compile")
      // errors related to annotation classes defined in main module
      assert(!testCompileRes.isSuccess)
    }
  }
}

object MillInitGradleFastCsvTests extends BuildGenTestSuite {

  def tests: Tests = Tests {
    // - multi-module
    // - JUnit 5
    // - requires Java 17+
    // - Gradle 8.10.1
    val url = "https://github.com/osiegmar/FastCSV/archive/refs/tags/v3.4.0.zip"

    test - integrationTest(url) { tester =>
      import tester._

      val cmd = (
        "init",
        "--base-module",
        "BaseModule",
        "--jvm-id",
        "17",
        "--deps-object",
        "Deps",
        "--merge"
      )
      val initRes = eval(cmd, stdout = os.Inherit, stderr = os.Inherit)
      assert(initRes.isSuccess)

      val compileRes = eval("lib.compile")
      assert(
        // classpath entry added by JavaModule.compileResources does not exist
        // error: warnings found and -Werror specified
        !compileRes.isSuccess
      )
    }
  }
}

object MillInitGradleEhcache3Tests extends BuildGenTestSuite {

  def tests: Tests = Tests {
    // - multi-level modules
    // - additional repository (Terracotta)
    // - JUnit 5
    // - Gradle 7.6.2
    val url = "https://github.com/ehcache/ehcache3/archive/refs/tags/v3.10.8.zip"
    val compileTasksSucceeding = Seq(
      "ehcache-api.compile",
      "osgi-test.compile",
      "spi-tester.compile",
      "ehcache-api.test.compile",
      "clustered.ops-tool.compile",
      "ehcache-api.test.compile",
      "clustered.integration-test.compile",
      "integration-test.compile",
      "clustered.osgi-test.compile",
      "clustered.ops-tool.test.compile",
      "clustered.ehcache-common-api.compile",
      "clustered.ehcache-common.compile",
      "clustered.test-utils.compile",
      "clustered.ehcache-common-api.test.compile"
    )
    // - warnings found and -Werror specified
    // - missing dependencies (unsupported configurations)
    // - missing additional sources
    // - missing generated sources
    // - upstream compilation fails
    val compileTasksFailing = Seq(
      "clustered.server.ehcache-service-api.test.compile",
      "clustered.server.ehcache-entity.compile",
      "clustered.ehcache-common.test.compile",
      "ehcache-core.compile",
      "ehcache-core.test.compile",
      "ehcache-impl.compile",
      "ehcache-impl.test.compile",
      "ehcache-xml.compile",
      "ehcache-xml.ehcache-xml-spi.compile",
      "ehcache-xml.ehcache-xml-spi.test.compile",
      "ehcache-transactions.compile",
      "clustered.server.ehcache-sthe diplomatervice-api.compile",
      "clustered.ehcache-client.compile",
      "ehcache-xml.test.compile",
      "ehcache-107.compile",
      "osgi-test.test.compile",
      "clustered.server.ehcache-service.compile",
      "integration-test.test.compile",
      "core-spi-test.compile",
      "demos.00-NoCache.compile",
      "clustered.server.ehcache-entity.test.compile",
      "demos.01-CacheAside.compile",
      "demos.01-CacheAside.test.compile",
      "clustered.ehcache-client.test.compile",
      "demos.00-NoCache.test.compile",
      "ehcache-107.test.compile",
      "clustered.osgi-test.test.compile",
      "core-spi-test.test.compile",
      "ehcache-management.compile",
      "ehcache-transactions.test.compile",
      "clustered.server.ehcache-service.test.compile",
      "clustered.integration-test.test.compile",
      "ehcache-management.test.compile"
    )

    test - integrationTest(url) { tester =>
      // Takes forever on windows
      if (!Util.isWindows) {
        import tester._

        val init = ("init", "--base-module", "BaseModule", "--deps-object", "Deps")
        val initRes = eval(init, stdout = os.Inherit, stderr = os.Inherit)
        assert(initRes.isSuccess)

        for (task <- compileTasksSucceeding) {
          assert(eval(task).isSuccess)
        }
        for (task <- compileTasksFailing) {
          assert(!eval(task).isSuccess)
        }
      }
    }
  }
}

object MillInitGradleMoshiTests extends BuildGenTestSuite {

  def tests: Tests = Tests {
    test {
      // - multi-module
      // - mixed Java/Kotlin sources (in same folder)
      // - JUnit 4
      // - Gradle 8.12
      val url = "https://github.com/square/moshi/archive/refs/tags/1.15.2.zip"
      val compileTasksSucceeding = Seq(
        "moshi.compile",
        "moshi-adapters.compile",
        "moshi.test.compile",
        "moshi-adapters.test.compile",
        "examples.compile",
        "moshi.records-tests.test.compile",
        // no sources (test only modules)
        "kotlin.tests.codegen-only.compile",
        "kotlin.tests.compile",
        "moshi.records-tests.compile",
        // no Java sources
        "kotlin.tests.extra-moshi-test-module.compile",
        "moshi-kotlin-codegen.compile",
        "moshi-kotlin.compile"
      )
      // - conversion does not support Kotlin
      val compileTasksFailing = Seq(
        "kotlin.tests.codegen-only.test.compile",
        "kotlin.tests.test.compile",
        "moshi-kotlin.test.compile",
        "moshi-kotlin-codegen.test.compile"
      )

      integrationTest(url) { tester =>
        import tester._

        val init = ("init", "--base-module", "BaseModule", "--deps-object", "Deps")
        val initRes = eval(init, stdout = os.Inherit, stderr = os.Inherit)
        assert(initRes.isSuccess)

        for (task <- compileTasksSucceeding) {
          assert(eval(task).isSuccess)
        }
        for (task <- compileTasksFailing) {
          assert(!eval(task).isSuccess)
        }
      }
    }
  }
}
