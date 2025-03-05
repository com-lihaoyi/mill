package foo
import utest._
object RandomTestsH extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Haven")
      assert(result == "Hello Haven")
      Thread.sleep(85)
      result
    }
    test("test2") {
      val result = Foo.greet("Iris")
      assert(result == "Hello Iris")
      Thread.sleep(26)
      result
    }
    test("test3") {
      val result = Foo.greet("Jazz")
      assert(result == "Hello Jazz")
      Thread.sleep(49)
      result
    }
    test("test4") {
      val result = Foo.greet("Kit")
      assert(result == "Hello Kit")
      Thread.sleep(37)
      result
    }
    test("test5") {
      val result = Foo.greet("Lake")
      assert(result == "Hello Lake")
      Thread.sleep(63)
      result
    }
  }
} 