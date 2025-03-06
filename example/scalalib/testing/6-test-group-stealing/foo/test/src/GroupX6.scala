package foo
import utest._
object GroupX6 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Perseus")
      assert(result == "Hello Perseus")
      Thread.sleep(41)
      result
    }
    test("test2") {
      val result = Foo.greet("Quirinus")
      assert(result == "Hello Quirinus")
      Thread.sleep(62)
      result
    }
    test("test3") {
      val result = Foo.greet("Rhea")
      assert(result == "Hello Rhea")
      Thread.sleep(28)
      result
    }
  }
} 