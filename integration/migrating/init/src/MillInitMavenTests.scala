package mill.integration

import utest.*

object MillInitMavenTests extends MillInitImportTestSuite {
  def tests = Tests {
    test("jansi") - checkImport(
      repoName = "jansi",
      initArgs = Seq("--mill-jvm-id", "17"),
      passingTasks = Seq("test"),
      failingTasks = Seq(Seq("spotless", "--check"))
    )

    test("netty") - checkImport(
      repoName = "netty",
      initArgs = Seq("--mill-jvm-id", "17"),
      passingTasks = Seq("common.compile")
    )
  }
}
