package bar

import utest._
import scala.scalanative.unsafe._

object BarTests extends TestSuite {
  def tests = Tests {
    test("simple one") {
      val result = HelloWorldBar.stringLength(c"hello")
      assert(result == 5)
      result
    }
  }
}
