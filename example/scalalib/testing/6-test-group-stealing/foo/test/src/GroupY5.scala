package foo
import utest._
object GroupY5 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Xiuhtecuhtli")
      assert(result == "Hello Xiuhtecuhtli")
      Thread.sleep(46)
      result
    }
    test("test2") {
      val result = Foo.greet("Yarilo")
      assert(result == "Hello Yarilo")
      Thread.sleep(62)
      result
    }
    test("test3") {
      val result = Foo.greet("Zephyrus")
      assert(result == "Hello Zephyrus")
      Thread.sleep(33)
      result
    }
    test("test4") {
      val result = Foo.greet("Aegir")
      assert(result == "Hello Aegir")
      Thread.sleep(54)
      result
    }
  }
} 