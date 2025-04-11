package app

import utest.*
import app.MyNumber

object MyNumberCombinatorTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      val a = MyNumber(1)
      val b = MyNumber(2)
      val c = MyNumber(3)
      val result = MyNumber.combine(a, b, c)
      assert(result == MyNumber(6))
    }
  }
}
