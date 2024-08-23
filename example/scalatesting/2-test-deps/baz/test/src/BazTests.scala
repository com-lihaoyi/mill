package baz
import utest._
object BazTests extends TestSuite {
  def tests = Tests {
    test("simple") {
      BazTestUtils.bazAssertEquals(Baz.value, 123)
    }
  }
}
