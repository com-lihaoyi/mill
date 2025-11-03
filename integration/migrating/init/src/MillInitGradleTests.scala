package mill.integration

import utest.*

object MillInitGradleTests extends MillInitImportTestSuite {
  def tests = Tests {
    test("FastCSV") - checkImport(
      gitUrl = "https://github.com/osiegmar/FastCSV.git",
      gitBranch = "v4.0.0",
      initArgs = Seq("--gradle-jvm-id", "24"),
      configsGoldenFile = "golden/gradle/fast-csv",
      failingTasks = Seq("lib.compile")
    )

    test("ehcache3") - checkImport(
      gitUrl = "https://github.com/ehcache/ehcache3.git",
      gitBranch = "v3.10.8",
      initArgs = Seq("--gradle-jvm-id", "11"),
      configsGoldenFile = "golden/gradle/ehcache3",
      failingTasks = Seq("ehcache-api.compile")
    )
  }
}
