package foo

import scala.scalanative.unsafe.*
import utest.*

object FooTests extends TestSuite {
  def tests = Tests {
    test("simple one") {
      val result = Foo.generateHtml("hello")
      assert(fromCString(result) == "<h1>hello</h1>\n")
      fromCString(result)
    }
    test("simple two") {
      val result = Foo.generateHtml("hello world")
      assert(fromCString(result) == "<h1>hello world</h1>\n")
      fromCString(result)
    }
  }
}
