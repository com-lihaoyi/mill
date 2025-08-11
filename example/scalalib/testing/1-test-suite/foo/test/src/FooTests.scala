package foo
import utest.*
object FooTests extends TestSuite {
  def tests = Tests {
    test("hello") {
      val result = Foo.hello()
      assert(result.startsWith("Hello"))
      result
    }
    test("world") {
      val result = Foo.hello()
      assert(result.endsWith("World"))
      result
    }
  }
}
