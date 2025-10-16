package mill.integration

import utest.*

object MillInitSbtScoptTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      "https://github.com/scopt/scopt.git",
      "v4.1.0",
      passingTasks = Seq("native[2.13.8].compile"),
      // no test module since verify framework is unsupported
      failingTasks = Seq("native[2.13.8].test")
    )
  }
}
