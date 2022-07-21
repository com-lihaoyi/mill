import org.scalatest._

class GreetSpec extends WordSpec with Matchers {
  "Greet" should {
    "work" in {
      Greet.greet("Nik", None) shouldBe ("Hello, Nik!")
    }
  }
}
