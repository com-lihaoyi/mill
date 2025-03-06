package foo
import utest._
object GroupX1 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val result = Foo.greet("Aether")
      assert(result == "Hello Aether")
      Thread.sleep(42)
      result
    }
    test("test2") {
      val result = Foo.greet("Boreas")
      assert(result == "Hello Boreas")
      Thread.sleep(18)
      result
    }
  }
}