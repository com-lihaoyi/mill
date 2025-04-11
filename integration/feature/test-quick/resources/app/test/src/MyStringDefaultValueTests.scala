package app

import utest.*
import app.MyString

object MyStringDefaultValueTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      val result = MyString.defaultValue
      assert(result == MyString(""))
    }
  }
}
