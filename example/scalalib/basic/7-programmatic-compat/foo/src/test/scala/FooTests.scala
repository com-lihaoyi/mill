package foo
import utest.*
object FooTests extends TestSuite {
  def tests = Tests {
    test("hello") {
      val result = Foo.hello()
      assert(result == "Hello World")
      result
    }
  }
}
