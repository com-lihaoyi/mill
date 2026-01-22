package mill.integration

import utest.*

object MillInitGradleTests extends MillInitImportTestSuite {
  def tests = Tests {
    test("FastCSV") - checkImport(
      repoName = "FastCSV",
      initArgs = Seq("--gradle-jvm-id", "25", "--mill-jvm-id", "25"),
      configsGoldenFile = "golden/gradle/fast-csv",
      passingTasks = Seq(Seq("resolve", "_"))
    )

    // Flaky
//    test("ehcache3") - checkImport(
//      repoName = "ehcache3",
//      initArgs = Seq("--gradle-jvm-id", "11", "--mill-jvm-id", "17"),
//      configsGoldenFile = "golden/gradle/ehcache3",
//      passingTasks = Seq(Seq("resolve", "_"))
//    )
  }
}
