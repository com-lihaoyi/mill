package foo
import utest.*
object FooIntegrationTests extends TestSuite {
  def tests = Tests {
    test("hello") {
      val result = Foo.hello()
      assert(result == "Hello World")
      result
    }
  }
}
