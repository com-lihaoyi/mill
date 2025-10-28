package mill.integration

import utest.*

object MillInitSbtScala3Tests extends MillInitTestSuite {
  def tests = Tests {
    // sbt 1.11.0
    test - checkImport(
      "https://github.com/scala/scala3.git",
      "3.7.1",
      failingTasks = Seq(("resolve", "_"))
    )
  }
}
