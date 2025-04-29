package foo
import utest._
object FooTest1 extends TestSuite {
  def tests = Tests {
    test("test1") {
      val name = "Aether"
      val greeted = Foo.greet(name)
      assert(greeted == s"Hello, $name!")
    }
  }
}
