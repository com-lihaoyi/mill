package mill.integration

import mill.constants.Util
import mill.integration.MillInitUtils.*
import mill.testkit.GitRepoIntegrationTestSuite
import utest.*

import scala.collection.immutable.{Seq, SortedSet}

object MillInitGradleJCommanderTests extends GitRepoIntegrationTestSuite {

  // gradle 8.9
  // single module
  // testng 7.0.0
  def gitRepoUrl = "git@github.com:cbeust/jcommander.git"
  def gitRepoBranch = "2.0"

  def tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      writeMillJvmVersion(workspacePath, "11")

      eval("init").isSuccess ==> true
      eval("publishLocal").isSuccess ==> true // implies compile

      // annotations defined in main-module cannot be used in test-module?
      eval("test.compile").err.linesIterator.exists(_.contains(
        "jcommander/src/test/java/com/beust/jcommander/ParameterOrderTest.java:33:23: cannot find symbol"
      )) ==> true
    }
  }
}

object MillInitGradleFastCsvTests extends GitRepoIntegrationTestSuite {

  // gradle 9.0.0-rc-1
  // Junit5
  def gitRepoUrl = "git@github.com:osiegmar/FastCSV.git"
  def gitRepoBranch = "v4.0.0"

  def tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*

      // from tasks.compileJava.options.release
      writeMillJvmVersion(workspacePath, "17")

      eval("init").isSuccess ==> true
      eval("lib.publishLocal").isSuccess ==> true // implies compile

      // requires javacOptions for Java modules
      eval("example.compile").err.linesIterator.exists(_.contains(
        "FastCSV/example/src/main/java/module-info.java:3:24: module not found: de.siegmar.fastcsv"
      )) ==> true

      // junit-platform-launcher version is set implicitly (tasks.test.useJunitPlatform())
      eval("lib.test.compile").err.linesIterator.exists(_.contains(
        "lib.test.resolvedMvnDeps coursier.error.ResolutionError$Several: Error downloading org.junit.platform:junit-platform-launcher:"
      )) ==> true
    }
  }
}

object MillInitGradleEhcache3Tests extends GitRepoIntegrationTestSuite {

  // gradle 7.2
  // custom dependency configurations
  // dependencies with version constraints
  // custom layout
  // custom repository
  // bom dependencies
  // modules with pom packaging
  // Junit4
  def gitRepoUrl = "git@github.com:ehcache/ehcache3.git"
  def gitRepoBranch = "v3.10.8"

  def tests: Tests = Tests {
    test - integrationTest { tester =>
      // Takes forever on windows
      if (!Util.isWindows) {
        writeMillJvmVersionTemurin11(tester.workspacePath)
        testMillInit(
          tester,
          initCommand = Seq("init"),
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
            "clustered.ops-tool.test.allSourceFiles" -> 1,
            "demos.01-CacheAside.allSourceFiles" -> 4,
            "clustered.ops-tool.allSourceFiles" -> 9,
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
              "clustered.ehcache-clustered.compile",
              "clustered.ehcache-common-api.compile",
              "clustered.ehcache-common-api.test.compile",
              "clustered.ehcache-common.compile",
              "clustered.ehcache-common.test.compile",
              "clustered.integration-test.compile",
              "clustered.ops-tool.compile",
              "clustered.ops-tool.test.compile",
              "clustered.osgi-test.compile",
              "clustered.test-utils.compile",
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
            failed = SortedSet(
              "clustered.ehcache-client.compile",
              "clustered.ehcache-client.test.compile",
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
              "clustered.ehcache-common.test",
              "clustered.ops-tool.test",
              "ehcache-api.test",
              "ehcache-core.test",
              "ehcache-xml.ehcache-xml-spi.test"
            ),
            failed = SortedSet(
              "clustered.ehcache-client.test",
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
