package foo
import utest._
object GroupY7 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Horus")
      assert(result == "Hello Horus")
      Thread.sleep(60)
      result
    }
    test("test2") {
      val result = Foo.greet("Isis")
      assert(result == "Hello Isis")
      Thread.sleep(44)
      result
    }
    test("test3") {
      val result = Foo.greet("Juno")
      assert(result == "Hello Juno")
      Thread.sleep(72)
      result
    }
  }
} 