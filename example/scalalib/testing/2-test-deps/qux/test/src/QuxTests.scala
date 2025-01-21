package qux
import utest._
object QuxTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      baz.BazTestUtils.bazAssertEquals(Qux.value, "xyz")
    }
  }
}
