package foo
import utest._
object GroupY10 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Rama")
      assert(result == "Hello Rama")
      Thread.sleep(49)
      result
    }
    test("test2") {
      val result = Foo.greet("Set")
      assert(result == "Hello Set")
      Thread.sleep(66)
      result
    }
    test("test3") {
      val result = Foo.greet("Thoth")
      assert(result == "Hello Thoth")
      Thread.sleep(35)
      result
    }
    test("test4") {
      val result = Foo.greet("Ukko")
      assert(result == "Hello Ukko")
      Thread.sleep(57)
      result
    }
  }
} 