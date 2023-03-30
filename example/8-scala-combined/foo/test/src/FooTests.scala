package foo
import utest._
object FooTests extends TestSuite {
  def tests = Tests {
    test("test") {
      val result = Foo.value.toString
      val matcher = "<h1>hello Scala [23].x</h1>".r
      assert(matcher.matches(result))
      result
    }
  }
}
