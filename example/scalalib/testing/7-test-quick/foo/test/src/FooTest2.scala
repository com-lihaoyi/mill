package foo
import utest._
object FooTest2 extends TestSuite {
  def tests = Tests {
    test("test2") {
      val name = "Bob"
      val greeted = Foo.greet2(name)
      assert(greeted == s"Hi, $name!")
    }
  }
}
