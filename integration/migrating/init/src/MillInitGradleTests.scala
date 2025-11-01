package mill.integration

import utest.*

object MillInitGradleTests extends MillInitImportTestSuite {
  def tests = Tests {
    test("FastCSV") - checkImport(
      gitUrl = "https://github.com/osiegmar/FastCSV.git",
      gitBranch = "v4.1.0",
      configsGoldenFile = "golden/gradle/fast-csv",
      passingTasks = Seq(("resolve", "_")),
      failingTasks = Seq("lib.compile"),
      envJvmId = "zulu:24"
    )

    test("ehcache3") - checkImport(
      gitUrl = "https://github.com/ehcache/ehcache3.git",
      gitBranch = "v3.11.1",
      configsGoldenFile = "golden/gradle/ehcache3",
      passingTasks = Seq(("resolve", "_")),
      failingTasks = Seq("ehcache-api.compile"),
      envJvmId = "zulu:17"
    )
  }
}
