package example

import utest.*

object UnrelatedTests extends TestSuite {
  val tests = Tests {
    test("still runs") {
      assert(1 + 1 == 2)
    }
  }
}
