package qux
import utest.*
object QuxTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      baz.BazTestUtils.bazAssertEquals(Qux.value, "xyz")
    }
  }
}
