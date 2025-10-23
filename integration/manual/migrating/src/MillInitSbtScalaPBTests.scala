package mill.integration

import utest.*

object MillInitSbtScalaPBTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      // sbt 1.11.2
      "https://github.com/scalapb/ScalaPB.git",
      "v0.11.19",
      failingTasks = Seq(("resolve", "_"))
    )
  }
}
