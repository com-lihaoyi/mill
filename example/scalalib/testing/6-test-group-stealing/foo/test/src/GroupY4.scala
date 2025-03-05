package foo
import utest._
object GroupY4 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Wotan")
      assert(result == "Hello Wotan")
      Thread.sleep(71)
      result
    }
  }
} 