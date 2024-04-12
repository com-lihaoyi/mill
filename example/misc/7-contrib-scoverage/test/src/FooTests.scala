package foo
import utest._
object FooTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      val result = Foo.generateHtml("hello")
      assert(result == "<h1>hello</h1>")
      result
    }
    test("escaping") {
      val result = Foo.generateHtml("<hello>")
      assert(result == "<h1>&lt;hello&gt;</h1>")
      result
    }
  }
}
