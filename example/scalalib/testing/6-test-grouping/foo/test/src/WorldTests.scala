package foo
import utest.*
object WorldTests extends TestSuite {
  def tests = Tests {
    test("world") {
      println("Testing World")
      val result = Foo.hello()
      assert(result.endsWith("World"))
      Thread.sleep(1000)
      println("Testing World Completed")
      result
    }
  }
}
