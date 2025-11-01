package mill.integration

import utest.*

object MillInitMavenTests extends MillInitImportTestSuite {
  def tests = Tests {
    test("jansi") - checkImport(
      gitUrl = "https://github.com/fusesource/jansi.git",
      gitBranch = "jansi-2.4.2",
      configsGoldenFile = "golden/maven/jansi",
      passingTasks = Seq("test")
    )

    test("netty") - checkImport(
      gitUrl = "https://github.com/netty/netty.git",
      gitBranch = "netty-4.2.7.Final",
      configsGoldenFile = "golden/maven/netty",
      passingTasks = Seq("codec-compression.compile"),
      failingTasks = Seq("codec-dns.compile")
    )
  }
}
