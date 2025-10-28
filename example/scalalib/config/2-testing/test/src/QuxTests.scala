package qux
import utest.*
object QuxTests extends TestSuite {
  def assertHello(result: String): Unit = {
    assert(result.startsWith("Hello"))
  }

  def assertWorld(result: String): Unit = {
    assert(result.endsWith("World"))
  }

  def tests = Tests {
    test("hello") {
      val result = Qux.hello()
      assertHello(result)
      result
    }
    test("world") {
      val result = Qux.hello()
      assertWorld(result)
      result
    }
  }
}
