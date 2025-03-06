package foo
import utest._
object GroupX3 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Fortuna")
      assert(result == "Hello Fortuna")
      Thread.sleep(25)
      result
    }
    test("test2") {
      val result = Foo.greet("Gaia")
      assert(result == "Hello Gaia")
      Thread.sleep(72)
      result
    }
    test("test3") {
      val result = Foo.greet("Helios")
      assert(result == "Hello Helios")
      Thread.sleep(38)
      result
    }
    test("test4") {
      val result = Foo.greet("Iris")
      assert(result == "Hello Iris")
      Thread.sleep(56)
      result
    }
  }
}