package foo
import utest.*
object HelloTests extends TestSuite {
  def tests = Tests {
    test("hello") {
      println("Testing Hello")
      val result = Foo.hello()
      assert(result.startsWith("Hello"))
      Thread.sleep(1000)
      println("Testing Hello Completed")
      result
    }
  }
}
