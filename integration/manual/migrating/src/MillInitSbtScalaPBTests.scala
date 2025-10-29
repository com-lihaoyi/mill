package mill.integration

import utest.*

object MillInitSbtScalaPBTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      // sbt 1.11.2
      // init fails due to use of sbt-projectmatrix plugin
      "https://github.com/scalapb/ScalaPB.git",
      "v0.11.19"
    )
  }
}
