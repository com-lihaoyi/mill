package foo
import utest._
object GroupX4 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Janus")
      assert(result == "Hello Janus")
      Thread.sleep(45)
      result
    }
    test("test2") {
      val result = Foo.greet("Kratos")
      assert(result == "Hello Kratos")
      Thread.sleep(68)
      result
    }
    test("test3") {
      val result = Foo.greet("Luna")
      assert(result == "Hello Luna")
      Thread.sleep(29)
      result
    }
    test("test4") {
      val result = Foo.greet("Maia")
      assert(result == "Hello Maia")
      Thread.sleep(52)
      result
    }
    test("test5") {
      val result = Foo.greet("Nyx")
      assert(result == "Hello Nyx")
      Thread.sleep(34)
      result
    }
  }
} 