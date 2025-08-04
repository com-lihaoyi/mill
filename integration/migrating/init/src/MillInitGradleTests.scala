package mill.integration

import mill.constants.Util
import mill.integration.MillInitUtils.*
import utest.*

import scala.collection.immutable.{Seq, SortedSet}

object MillInitGradleJCommanderTests extends BuildGenTestSuite {

  def tests: Tests = Tests {
    // - single module
    // - custom javacOptions
    // - TestNg
    // - Gradle 8.9
    val url = "https://github.com/cbeust/jcommander/archive/refs/tags/2.0.zip"

    test - integrationTest(url)(
      testMillInit(
        _,
        expectedAllSourceFileNums = Map("allSourceFiles" -> 65, "test.allSourceFiles" -> 107),
        expectedCompileTaskResults =
          Some(SplitTaskResults(
            successful = SortedSet("compile"),
            // errors related to annotation classes defined in main module
            failed = SortedSet("test.compile")
          )),
        expectedTestTaskResults =
          Some(SplitTaskResults(successful = SortedSet(), failed = SortedSet("test")))
      )
    )
  }
}

object MillInitGradleFastCsvTests extends BuildGenTestSuite {

  def tests: Tests = Tests {
    // - multi-module
    // - JUnit 5
    // - requires Java 17+
    // - Gradle 8.10.1
    val url = "https://github.com/osiegmar/FastCSV/archive/refs/tags/v3.4.0.zip"

    test - integrationTest(url)(
      testMillInit(
        _,
        initCommand = defaultInitCommand ++ Seq("--jvm-id", "17"),
        expectedAllSourceFileNums = Map(
          "example.allSourceFiles" -> 24,
          "lib.allSourceFiles" -> 41,
          "lib.test.allSourceFiles" -> 9
        ),
        expectedCompileTaskResults = Some(SplitTaskResults(
          successful = SortedSet("lib.compile"),
          failed = SortedSet(
            "example.compile",
            "lib.test.compile"
          )
        )),
        expectedTestTaskResults =
          Some(SplitTaskResults(successful = SortedSet(), failed = SortedSet("lib.test")))
      )
    )
  }
}

object MillInitGradleEhcache3Tests extends BuildGenTestSuite {

  def tests: Tests = Tests {
    // - multi-level modules
    // - additional repository (Terracotta)
    // - JUnit 5
    // - Gradle 7.6.2
    val url = "https://github.com/ehcache/ehcache3/archive/refs/tags/v3.10.8.zip"

    test - integrationTest(url) { tester =>
      // Takes forever on windows
      if (!Util.isWindows) {
        writeMillJvmVersionTemurin11(tester.workspacePath)
        testMillInit(
          tester,
          expectedAllSourceFileNums = Map(
            "ehcache-xml.test.allSourceFiles" -> 55,
            "ehcache-xml.ehcache-xml-spi.allSourceFiles" -> 7,
            "ehcache-xml.ehcache-xml-spi.test.allSourceFiles" -> 1,
            "ehcache-api.test.allSourceFiles" -> 5,
            "clustered.test-utils.allSourceFiles" -> 2,
            "osgi-test.allSourceFiles" -> 1,
            "clustered.integration-test.allSourceFiles" -> 0,
            "clustered.ehcache-common.test.allSourceFiles" -> 17,
            "ehcache-core.test.allSourceFiles" -> 61,
            "clustered.server.ehcache-service.allSourceFiles" -> 12,
            "clustered.ehcache-client.allSourceFiles" -> 82,
            "demos.allSourceFiles" -> 0,
            "ehcache-core.allSourceFiles" -> 120,
            "clustered.server.ehcache-entity.allSourceFiles" -> 38,
            "integration-test.allSourceFiles" -> 0,
            "clustered.server.ehcache-service-api.allSourceFiles" -> 14,
            "spi-tester.allSourceFiles" -> 8,
            "clustered.osgi-test.allSourceFiles" -> 0,
            "ehcache-107.test.allSourceFiles" -> 31,
            "integration-test.test.allSourceFiles" -> 44,
            "clustered.server.ehcache-service.test.allSourceFiles" -> 16,
            "ehcache-management.allSourceFiles" -> 37,
            "ehcache-xml.allSourceFiles" -> 33,
            "clustered.integration-test.test.allSourceFiles" -> 59,
            "ehcache.allSourceFiles" -> 0,
            "ehcache-transactions.test.allSourceFiles" -> 21,
            "clustered.ehcache-common.allSourceFiles" -> 48,
            "demos.00-NoCache.allSourceFiles" -> 3,
            "ehcache-management.test.allSourceFiles" -> 14,
            "clustered.ehcache-common-api.test.allSourceFiles" -> 1,
            "clustered.server.ehcache-service-api.test.allSourceFiles" -> 2,
            "ehcache-transactions.allSourceFiles" -> 12,
            "ehcache-107.allSourceFiles" -> 38,
            "clustered.allSourceFiles" -> 0,
            "clustered.ops-tool.test.allSourceFiles" -> 1,
            "demos.01-CacheAside.allSourceFiles" -> 4,
            "clustered.ops-tool.allSourceFiles" -> 9,
            "clustered.server.allSourceFiles" -> 0,
            "docs.allSourceFiles" -> 0,
            "clustered.server.ehcache-entity.test.allSourceFiles" -> 16,
            "ehcache-impl.test.allSourceFiles" -> 182,
            "ehcache-api.allSourceFiles" -> 79,
            "clustered.osgi-test.test.allSourceFiles" -> 3,
            "osgi-test.test.allSourceFiles" -> 6,
            "clustered.ehcache-common-api.allSourceFiles" -> 20,
            "clustered.ehcache-client.test.allSourceFiles" -> 86,
            "ehcache-impl.allSourceFiles" -> 189,
            "clustered.ehcache-clustered.allSourceFiles" -> 0,
            "core-spi-test.allSourceFiles" -> 43
          ),
          expectedCompileTaskResults = Some(SplitTaskResults(
            successful = SortedSet(
              "clustered.compile",
              "clustered.ehcache-clustered.compile",
              "clustered.ehcache-common-api.compile",
              "clustered.ehcache-common-api.test.compile",
              "clustered.ehcache-common.compile",
              "clustered.integration-test.compile",
              "clustered.ops-tool.compile",
              "clustered.ops-tool.test.compile",
              "clustered.osgi-test.compile",
              "clustered.server.compile",
              "clustered.test-utils.compile",
              "demos.compile",
              "docs.compile",
              "ehcache-api.compile",
              "ehcache-api.test.compile",
              "ehcache-core.compile",
              "ehcache-core.test.compile",
              "ehcache-xml.ehcache-xml-spi.compile",
              "ehcache-xml.ehcache-xml-spi.test.compile",
              "ehcache.compile",
              "integration-test.compile",
              "osgi-test.compile",
              "spi-tester.compile"
            ),
            // [warn] Unexpected javac output: warning: [path] bad path element...ehcache-api/compile-resources": no such file or directory
            // [warn] error: warnings found and -Werror specified
            failed = SortedSet(
              "clustered.ehcache-client.compile",
              "clustered.ehcache-client.test.compile",
              "clustered.ehcache-common.test.compile",
              "clustered.integration-test.test.compile",
              "clustered.osgi-test.test.compile",
              "clustered.server.ehcache-entity.compile",
              "clustered.server.ehcache-entity.test.compile",
              "clustered.server.ehcache-service-api.compile",
              "clustered.server.ehcache-service-api.test.compile",
              "clustered.server.ehcache-service.compile",
              "clustered.server.ehcache-service.test.compile",
              "core-spi-test.compile",
              "demos.00-NoCache.compile",
              "demos.01-CacheAside.compile",
              "ehcache-107.compile",
              "ehcache-107.test.compile",
              "ehcache-impl.compile",
              "ehcache-impl.test.compile",
              "ehcache-management.compile",
              "ehcache-management.test.compile",
              "ehcache-transactions.compile",
              "ehcache-transactions.test.compile",
              "ehcache-xml.compile",
              "ehcache-xml.test.compile",
              "integration-test.test.compile",
              "osgi-test.test.compile"
            )
          )),
          expectedTestTaskResults = Some(SplitTaskResults(
            successful = SortedSet(
              "clustered.ehcache-common-api.test",
              "clustered.ops-tool.test",
              "ehcache-api.test",
              "ehcache-core.test",
              "ehcache-xml.ehcache-xml-spi.test"
            ),
            failed = SortedSet(
              "clustered.ehcache-client.test",
              "clustered.ehcache-common.test",
              "clustered.integration-test.test",
              "clustered.osgi-test.test",
              "clustered.server.ehcache-entity.test",
              "clustered.server.ehcache-service-api.test",
              "clustered.server.ehcache-service.test",
              "ehcache-107.test",
              "ehcache-impl.test",
              "ehcache-management.test",
              "ehcache-transactions.test",
              "ehcache-xml.test",
              "integration-test.test",
              "osgi-test.test"
            )
          ))
        )
      }
    }
  }
}
