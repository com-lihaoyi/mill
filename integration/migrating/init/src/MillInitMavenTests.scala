package mill.integration

import utest.*

object MillInitMavenTests extends MillInitImportTestSuite {
  def tests = Tests {
    test("jansi") - checkImport(
      repoName = "jansi",
      initArgs = Seq("--mill-jvm-id", "17"),
      configsGoldenFile = "golden/maven/jansi",
      passingTasks = Seq("test")
    )

    test("netty") - checkImport(
      repoName = "netty",
      initArgs = Seq("--mill-jvm-id", "17"),
      configsGoldenFile = "golden/maven/netty",
      passingTasks = Seq("common.compile")
    )
  }
}
