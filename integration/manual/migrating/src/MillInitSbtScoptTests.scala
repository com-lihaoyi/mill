package mill.integration

import utest.*

object MillInitSbtScoptTests extends MillInitTestSuite {
  def tests = Tests {
    test("upgraded") - checkImport(
      "https://github.com/scopt/scopt.git",
      "v4.1.0",
      passingTasks = Seq("__.compile"),
      failingTasks = Seq(
        // verify test framework is not supported
        "__.test"
      )
    )
  }
}
