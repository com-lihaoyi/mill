package bar
import utest._
object BarTests extends TestSuite {
  def tests = Tests {
    test("hello") {
      val result = Bar.hello()
      assert(result.startsWith("Hello"))
      result
    }
    test("world") {
      val result = Bar.hello()
      assert(result.endsWith("World"))
      result
    }
  }
}
