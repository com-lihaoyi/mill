package foo
import utest._
object Foo1Tests extends TestSuite {
  def tests = Tests {
    test("hello") {
      println("Testing Hello 1")
      val result = Foo.hello()
      assert(result.startsWith("Hello"))
      Thread.sleep(10000)
      println("Testing Hello 1 Completed")
      result
    }
    test("world") {
      println("Testing World 1")
      val result = Foo.hello()
      assert(result.endsWith("World"))
      Thread.sleep(10000)
      println("Testing World 1 Completed")
      result
    }
  }
}
