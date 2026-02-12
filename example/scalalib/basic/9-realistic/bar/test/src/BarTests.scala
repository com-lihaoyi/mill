package bar
import utest._
object BarTests extends TestSuite {
  def tests = Tests {
    test("test") {
      val result = Bar.value.toString
      val matcher = "<p>world Specific code for Scala [23].x</p>".r
      assert(matcher.matches(result))
      result
    }
  }
}
