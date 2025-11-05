package mill.integration

import utest.*

object MillInitSbtScalaLoggingTests extends MillInitTestSuite {
  def tests = Tests {
    test("upgraded") - checkImport(
      "https://github.com/lightbend-labs/scala-logging.git",
      "v3.9.6",
      passingTasks = Seq("__.test")
    )
  }
}
