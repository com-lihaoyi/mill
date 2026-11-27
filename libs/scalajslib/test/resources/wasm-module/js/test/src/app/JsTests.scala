package app

import utest._

object JsTests extends TestSuite {
  val tests = Tests {
    test("answer") {
      assert(App.twice(21) == 42)
    }
  }
}
