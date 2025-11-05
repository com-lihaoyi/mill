package mill.integration

import utest.*

object MillInitSbtScala3Tests extends MillInitTestSuite {
  def tests = Tests {
    // multiple SBT projects share the same base directory
    test("upgraded") - assertThrows[MillInitFailed] {
      checkImport(
        "https://github.com/scala/scala3.git",
        "3.7.3"
      )
    }
  }
}
