package foo
import utest._
object GroupY8 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Khonsu")
      assert(result == "Hello Khonsu")
      Thread.sleep(53)
      result
    }
    test("test2") {
      val result = Foo.greet("Leto")
      assert(result == "Hello Leto")
      Thread.sleep(31)
      result
    }
  }
} 