package foo
import utest.*
object FooTests extends TestSuite {
  def tests = Tests {
    test("hello") {
      val result = Foo.hello()
      assert(result == "<h1>Hello World</h1>")
      result
    }
  }
}
