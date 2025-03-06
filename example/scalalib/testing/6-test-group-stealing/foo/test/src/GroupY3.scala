package foo
import utest._
object GroupY3 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Ra")
      assert(result == "Hello Ra")
      Thread.sleep(47)
      result
    }
    test("test2") {
      val result = Foo.greet("Saturn")
      assert(result == "Hello Saturn")
      Thread.sleep(85)
      result
    }
    test("test3") {
      val result = Foo.greet("Thor")
      assert(result == "Hello Thor")
      Thread.sleep(39)
      result
    }
    test("test4") {
      val result = Foo.greet("Ullr")
      assert(result == "Hello Ullr")
      Thread.sleep(58)
      result
    }
    test("test5") {
      val result = Foo.greet("Venus")
      assert(result == "Hello Venus")
      Thread.sleep(24)
      result
    }
  }
} 