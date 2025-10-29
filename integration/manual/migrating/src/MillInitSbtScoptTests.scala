package mill.integration

import utest.*

object MillInitSbtScoptTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      // sbt 1.5.2
      "https://github.com/scopt/scopt.git",
      "v4.1.0",
      initArgs = Seq("--sbt-jvm-id", "11"),
      passingTasks = Seq("jvm[2.11.12].compile"),
      failingTasks = Seq("jvm[2.12.16].compile", "jvm[2.13.8].compile", "jvm[3.1.3].compile")
    )
  }
}
