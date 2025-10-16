package mill.integration

import utest.*

object MillInitSbtScalaLoggingTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/lightbend-labs/scala-logging.git",
      "v3.9.5",
      passingTasks = Seq("_.test")
    )
  }
}
