package qux
import utest._
object QuxTests extends TestSuite {
  def tests = Tests {
    test("hello") {
      val result = Qux.hello()
      assert(result.startsWith("Hello"))
      result
    }
    test("world") {
      val result = Qux.hello()
      assert(result.endsWith("World"))
      result
    }
  }
}
