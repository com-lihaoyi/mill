package foo
import utest._
object RandomTestsF extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Winter")
      assert(result == "Hello Winter")
      Thread.sleep(59)
      result
    }
    test("test2") {
      val result = Foo.greet("Xander")
      assert(result == "Hello Xander")
      Thread.sleep(24)
      result
    }
    test("test3") {
      val result = Foo.greet("Yara")
      assert(result == "Hello Yara")
      Thread.sleep(78)
      result
    }
    test("test4") {
      val result = Foo.greet("Zephyr")
      assert(result == "Hello Zephyr")
      Thread.sleep(33)
      result
    }
    test("test5") {
      val result = Foo.greet("Atlas")
      assert(result == "Hello Atlas")
      Thread.sleep(46)
      result
    }
    test("test6") {
      val result = Foo.greet("Blair")
      assert(result == "Hello Blair")
      Thread.sleep(18)
      result
    }
    test("test7") {
      val result = Foo.greet("Cruz")
      assert(result == "Hello Cruz")
      Thread.sleep(62)
      result
    }
    test("test8") {
      val result = Foo.greet("Dawn")
      assert(result == "Hello Dawn")
      Thread.sleep(29)
      result
    }
    test("test9") {
      val result = Foo.greet("Echo")
      assert(result == "Hello Echo")
      Thread.sleep(54)
      result
    }
  }
} 