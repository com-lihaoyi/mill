import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GreetSpec extends AnyWordSpec with Matchers {
  "Greet" should {
    "work" in {
      Greet.greet("Nik", None) shouldBe ("Hello, Nik!")
    }
  }
}
