package qux
import utest._
object QuxIntegrationTests extends TestSuite {
  def tests = Tests {
    test("helloworld") {
      val result = Qux.hello()
      assert(result == "Hello World")
      result
    }
  }
}
