package foo
import utest._
object GroupX8 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Uranus")
      assert(result == "Hello Uranus")
      Thread.sleep(48)
      result
    }
    test("test2") {
      val result = Foo.greet("Vesta")
      assert(result == "Hello Vesta")
      Thread.sleep(75)
      result
    }
    test("test3") {
      val result = Foo.greet("Woden")
      assert(result == "Hello Woden")
      Thread.sleep(36)
      result
    }
    test("test4") {
      val result = Foo.greet("Xipe")
      assert(result == "Hello Xipe")
      Thread.sleep(54)
      result
    }
  }
} 