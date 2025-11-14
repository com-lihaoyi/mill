package mill.integration

import utest.*

object MillInitSbtScalaLoggingTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      // sbt 1.6.2
      "https://github.com/lightbend-labs/scala-logging.git",
      "v3.9.5",
      initArgs = Seq("--sbt-jvm-id", "17"),
      passingTasks = Seq("[2.11.12].compile"),
      failingTasks = Seq("[2.12.15].compile", "[2.13.8].compile", "[3.1.2].compile")
    )
  }
}
