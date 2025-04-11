package app

import utest.*
import app.MyString

object MyStringCombinatorTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      val a = MyString("a")
      val b = MyString("b")
      val c = MyString("c")
      val result = MyString.combine(a, b, c)
      assert(result == MyString("abc"))
    }
  }
}
