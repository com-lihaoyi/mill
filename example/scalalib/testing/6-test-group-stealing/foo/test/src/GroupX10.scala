package foo
import utest._
object GroupX10 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Echo")
      assert(result == "Hello Echo")
      Thread.sleep(55)
      result
    }
    test("test2") {
      val result = Foo.greet("Faunus")
      assert(result == "Hello Faunus")
      Thread.sleep(43)
      result
    }
  }
} 