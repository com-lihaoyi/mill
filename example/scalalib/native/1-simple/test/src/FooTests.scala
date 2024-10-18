package foo

import scala.scalanative.unsafe._
import utest._

object FooTests extends TestSuite {
  def tests = Tests {
    test("simple one") {
      val result = Foo.generateHtml("hello")
      val colored = Console.RED + "<h1>hello</h1>" + Console.RESET + "\n"
      assert(fromCString(result) == colored)
      fromCString(result)
    }
    test("simple two") {
      val result = Foo.generateHtml("hello world")
      val colored = Console.RED + "<h1>hello world</h1>" + Console.RESET + "\n"
      assert(fromCString(result) == colored)
      fromCString(result)
    }
  }
}
