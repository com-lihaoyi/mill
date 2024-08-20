package mill.scalalib

import org.scalatest.freespec.AnyFreeSpec

class ScalaTestSpec extends AnyFreeSpec {

  test("A Set") {
    test("when empty") {
      "should have size 0" in {
        assert(Set.empty.size == 0)
      }

      "should produce NoSuchElementException when head is invoked" in {
        assertThrows[NoSuchElementException] {
          Set.empty.head
        }
      }
    }
  }
}
