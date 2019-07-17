package tests
import org.scalatest.FunSuite

object BspTests extends FunSuite {
  test("test 1") {
    assert(new CoreTests().coreValue > 0)
  }
}
