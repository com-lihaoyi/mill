package foo
import utest._
object FooTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      assert(Foo.lineCount == 12)
    }
  }
}
