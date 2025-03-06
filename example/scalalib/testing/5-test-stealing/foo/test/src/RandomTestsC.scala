package foo
import utest._
object RandomTestsC extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Jordan")
      assert(result == "Hello Jordan")
      Thread.sleep(64)
      result
    }
  }
} 