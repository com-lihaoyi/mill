package foo
import utest._
object GroupX5 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Orion")
      assert(result == "Hello Orion")
      Thread.sleep(73)
      result
    }
  }
} 