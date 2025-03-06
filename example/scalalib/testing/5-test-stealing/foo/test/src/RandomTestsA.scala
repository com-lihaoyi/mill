package foo
import utest._
object RandomTestsA extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Aaron")
      assert(result == "Hello Aaron")
      Thread.sleep(32)
      result
    }
    test("test2") {
      val result = Foo.greet("Bella")
      assert(result == "Hello Bella")
      Thread.sleep(15)
      result
    }
    test("test3") {
      val result = Foo.greet("Cameron")
      assert(result == "Hello Cameron")
      Thread.sleep(48)
      result
    }
  }
} 