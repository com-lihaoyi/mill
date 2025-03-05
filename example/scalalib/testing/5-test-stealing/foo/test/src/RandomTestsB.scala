package foo
import utest._
object RandomTestsB extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Dakota")
      assert(result == "Hello Dakota")
      Thread.sleep(22)
      result
    }
    test("test2") {
      val result = Foo.greet("Ethan")
      assert(result == "Hello Ethan")
      Thread.sleep(75)
      result
    }
    test("test3") {
      val result = Foo.greet("Felix")
      assert(result == "Hello Felix")
      Thread.sleep(18)
      result
    }
    test("test4") {
      val result = Foo.greet("Gabriel")
      assert(result == "Hello Gabriel")
      Thread.sleep(43)
      result
    }
    test("test5") {
      val result = Foo.greet("Harper")
      assert(result == "Hello Harper")
      Thread.sleep(29)
      result
    }
    test("test6") {
      val result = Foo.greet("Isaac")
      assert(result == "Hello Isaac")
      Thread.sleep(51)
      result
    }
  }
} 