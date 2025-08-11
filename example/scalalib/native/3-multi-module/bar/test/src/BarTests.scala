package bar

import utest.*
import scala.scalanative.unsafe.*

object BarTests extends TestSuite {
  def tests = Tests {
    test("simple one") {
      val result = HelloWorldBar.stringLength(c"hello")
      assert(result == 5)
      result
    }
  }
}
