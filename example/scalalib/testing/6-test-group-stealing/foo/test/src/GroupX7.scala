package foo
import utest._
object GroupX7 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Selene")
      assert(result == "Hello Selene")
      Thread.sleep(59)
      result
    }
    test("test2") {
      val result = Foo.greet("Themis")
      assert(result == "Hello Themis")
      Thread.sleep(32)
      result
    }
  }
} 