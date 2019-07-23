package tests
import org.scalatest.FunSuite
import org.apache.commons.io.FileUtils

class BspTests extends FunSuite {
  val wrongVal: String = 3
  test("test 1") {
    assert(CoreTests().coreValue > 0)
  }
}
