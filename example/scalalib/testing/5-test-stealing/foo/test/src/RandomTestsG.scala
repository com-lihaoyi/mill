package foo
import utest._
object RandomTestsG extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Finn")
      assert(result == "Hello Finn")
      Thread.sleep(41)
      result
    }
    test("test2") {
      val result = Foo.greet("Gray")
      assert(result == "Hello Gray")
      Thread.sleep(73)
      result
    }
  }
} 