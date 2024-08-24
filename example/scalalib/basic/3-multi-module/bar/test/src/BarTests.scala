package bar
import utest._
object BarTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      val result = Bar.generateHtml("hello")
      assert(result == "<h1>hello</h1>")
      result
    }
    test("escaping") {
      val result = Bar.generateHtml("<hello>")
      assert(result == "<h1>&lt;hello&gt;</h1>")
      result
    }
  }
}
