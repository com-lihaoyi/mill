package app

import utest.*
import app.MyNumber

object MyNumberDefaultValueTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      val result = MyNumber.defaultValue
      assert(result == MyNumber(0))
    }
  }
}
