package foo
import utest._
object GroupX2 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Chronos")
      assert(result == "Hello Chronos")
      Thread.sleep(53)
      result
    }
    test("test2") {
      val result = Foo.greet("Demeter")
      assert(result == "Hello Demeter")
      Thread.sleep(31)
      result
    }
    test("test3") {
      val result = Foo.greet("Eos")
      assert(result == "Hello Eos")
      Thread.sleep(64)
      result
    }
  }
}