package foo
import utest._
object FooMoreTests extends TestSuite {
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
