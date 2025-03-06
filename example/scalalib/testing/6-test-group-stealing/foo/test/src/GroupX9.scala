package foo
import utest._
object GroupX9 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Ymir")
      assert(result == "Hello Ymir")
      Thread.sleep(67)
      result
    }
    test("test2") {
      val result = Foo.greet("Zeus")
      assert(result == "Hello Zeus")
      Thread.sleep(23)
      result
    }
    test("test3") {
      val result = Foo.greet("Atlas")
      assert(result == "Hello Atlas")
      Thread.sleep(49)
      result
    }
    test("test4") {
      val result = Foo.greet("Balder")
      assert(result == "Hello Balder")
      Thread.sleep(38)
      result
    }
    test("test5") {
      val result = Foo.greet("Ceres")
      assert(result == "Hello Ceres")
      Thread.sleep(82)
      result
    }
    test("test6") {
      val result = Foo.greet("Diana")
      assert(result == "Hello Diana")
      Thread.sleep(27)
      result
    }
  }
} 