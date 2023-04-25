package baz
object BazTestUtils {
  def bazAssertEquals(x: Any, y: Any) = {
    println("Using BazTestUtils.bazAssertEquals")
    assert(x == y)
  }
}
