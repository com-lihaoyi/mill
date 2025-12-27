package mill.integration

import utest.*

object MillInitMavenTests extends MillInitImportTestSuite {
  def tests = Tests {
    test("jansi") - checkImport(
      gitUrl = "https://github.com/fusesource/jansi.git",
      gitBranch = "jansi-2.4.2",
      initArgs = Seq("--mill-jvm-id", "17"),
      configsGoldenFile = "golden/maven/jansi",
      passingTasks = Seq("test")
    )

    test("netty") - checkImport(
      gitUrl = "https://github.com/netty/netty.git",
      gitBranch = "netty-4.2.6.Final",
      initArgs = Seq("--mill-jvm-id", "17"),
      configsGoldenFile = "golden/maven/netty",
      passingTasks = Seq("common.compile")
    )
  }
}
