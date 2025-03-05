package foo
import utest._
object RandomTestsD extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Kai")
      assert(result == "Hello Kai")
      Thread.sleep(38)
      result
    }
    test("test2") {
      val result = Foo.greet("Luna")
      assert(result == "Hello Luna")
      Thread.sleep(19)
      result
    }
    test("test3") {
      val result = Foo.greet("Mason")
      assert(result == "Hello Mason")
      Thread.sleep(82)
      result
    }
    test("test4") {
      val result = Foo.greet("Nova")
      assert(result == "Hello Nova")
      Thread.sleep(27)
      result
    }
    test("test5") {
      val result = Foo.greet("Owen")
      assert(result == "Hello Owen")
      Thread.sleep(56)
      result
    }
    test("test6") {
      val result = Foo.greet("Piper")
      assert(result == "Hello Piper")
      Thread.sleep(34)
      result
    }
    test("test7") {
      val result = Foo.greet("Quinn")
      assert(result == "Hello Quinn")
      Thread.sleep(42)
      result
    }
    test("test8") {
      val result = Foo.greet("River")
      assert(result == "Hello River")
      Thread.sleep(15)
      result
    }
  }
} 