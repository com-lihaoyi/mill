package bar
import utest._
import scala.scalanative.unsafe._

object BarTests extends TestSuite {
  def tests = Tests {
    test("simple one") {
      val result = HelloWorldBar.generateHtml(c"hello")
      assert(result == "<h1>hello</h1>")
      fromCString(result)
    }
    test("simple two") {
      val result = HelloWorldBar.generateHtml(c"<hello world>")
      assert(result == "<h1>hello world</h1>")
      fromCString(result)
    }
  }
}
