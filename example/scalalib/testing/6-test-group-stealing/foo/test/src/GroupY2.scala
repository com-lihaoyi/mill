package foo
import utest._
object GroupY2 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Odin")
      assert(result == "Hello Odin")
      Thread.sleep(51)
      result
    }
    test("test2") {
      val result = Foo.greet("Pluto")
      assert(result == "Hello Pluto")
      Thread.sleep(34)
      result
    }
    test("test3") {
      val result = Foo.greet("Quetzal")
      assert(result == "Hello Quetzal")
      Thread.sleep(63)
      result
    }
  }
} 