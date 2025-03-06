package foo
import utest._
object RandomTestsJ extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Storm")
      assert(result == "Hello Storm")
      Thread.sleep(44)
      result
    }
    test("test2") {
      val result = Foo.greet("True")
      assert(result == "Hello True")
      Thread.sleep(67)
      result
    }
    test("test3") {
      val result = Foo.greet("Vale")
      assert(result == "Hello Vale")
      Thread.sleep(25)
      result
    }
  }
} 