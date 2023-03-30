package foo
import utest._
object FooTests extends TestSuite {
  def tests = Tests {
    test("test") {
      val result = Foo.value.toString
      assert(result == "<h1>hello</h1>")
      result
    }
  }
}
