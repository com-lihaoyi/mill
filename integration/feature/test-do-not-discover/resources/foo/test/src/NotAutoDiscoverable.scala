package foo

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite

@DoNotDiscover
class NotAutoDiscoverable extends AnyFunSuite {
  test("example") {
    assert(true)
  }
}
