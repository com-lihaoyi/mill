package foo
import utest._
object RandomTestsI extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Mars")
      assert(result == "Hello Mars")
      Thread.sleep(52)
      result
    }
    test("test2") {
      val result = Foo.greet("North")
      assert(result == "Hello North")
      Thread.sleep(28)
      result
    }
    test("test3") {
      val result = Foo.greet("Onyx")
      assert(result == "Hello Onyx")
      Thread.sleep(69)
      result
    }
    test("test4") {
      val result = Foo.greet("Phoenix")
      assert(result == "Hello Phoenix")
      Thread.sleep(34)
      result
    }
    test("test5") {
      val result = Foo.greet("Quest")
      assert(result == "Hello Quest")
      Thread.sleep(47)
      result
    }
    test("test6") {
      val result = Foo.greet("Rain")
      assert(result == "Hello Rain")
      Thread.sleep(19)
      result
    }
    test("test7") {
      val result = Foo.greet("Sky")
      assert(result == "Hello Sky")
      Thread.sleep(58)
      result
    }
  }
} 