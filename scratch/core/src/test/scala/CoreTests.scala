package tests
import org.scalatest.FlatSpec

class CoreTests extends FlatSpec {
  val coreValue = 1
  "This simple addition" should "equal 4" in {
    assert(2 + 2 == 4)
  }
}
