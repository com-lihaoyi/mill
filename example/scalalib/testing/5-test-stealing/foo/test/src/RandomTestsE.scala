package foo
import utest._
object RandomTestsE extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Sage")
      assert(result == "Hello Sage")
      Thread.sleep(68)
      result
    }
    test("test2") {
      val result = Foo.greet("Talia")
      assert(result == "Hello Talia")
      Thread.sleep(23)
      result
    }
    test("test3") {
      val result = Foo.greet("Urban")
      assert(result == "Hello Urban")
      Thread.sleep(45)
      result
    }
    test("test4") {
      val result = Foo.greet("Violet")
      assert(result == "Hello Violet")
      Thread.sleep(31)
      result
    }
  }
} 