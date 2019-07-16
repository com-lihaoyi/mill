package tests
import org.scalatest.FunSuite

object BspTests extends FunSuite {
  test("test 1") {
    assert(CoreTests().coreValue > 0)
  }
}
