package mill.integration

import utest.*

object MillInitSbtScalaPBTests extends MillInitTestSuite {
  def tests = Tests {
    // sbt-projectmatrix plugin is not supported
    test("realistic") - assertThrows[MillInitFailed] {
      checkImport(
        "https://github.com/scalapb/ScalaPB.git",
        "v0.11.20"
      )
    }
  }
}
