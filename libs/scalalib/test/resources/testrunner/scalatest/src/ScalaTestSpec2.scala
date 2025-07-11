package mill.scalalib

import org.scalatest.Tag
import org.scalatest.freespec.AnyFreeSpec

class ScalaTestSpec2 extends AnyFreeSpec {

  "A Set 2" - {
    "when empty" - {
      "should have size 0" in {
        assert(Set.empty.size == 0)
      }

      "should produce NoSuchElementException when head is invoked" in {
        assertThrows[NoSuchElementException] {
          Set.empty.head
        }
      }

      "should be tagged" taggedAs (TaggedTest) in {
        assert(true)
      }
    }
  }
}
