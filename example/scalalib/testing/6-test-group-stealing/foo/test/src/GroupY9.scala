package foo
import utest._
object GroupY9 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Minerva")
      assert(result == "Hello Minerva")
      Thread.sleep(68)
      result
    }
    test("test2") {
      val result = Foo.greet("Nike")
      assert(result == "Hello Nike")
      Thread.sleep(40)
      result
    }
    test("test3") {
      val result = Foo.greet("Osiris")
      assert(result == "Hello Osiris")
      Thread.sleep(55)
      result
    }
    test("test4") {
      val result = Foo.greet("Pan")
      assert(result == "Hello Pan")
      Thread.sleep(27)
      result
    }
    test("test5") {
      val result = Foo.greet("Qebui")
      assert(result == "Hello Qebui")
      Thread.sleep(76)
      result
    }
  }
} 