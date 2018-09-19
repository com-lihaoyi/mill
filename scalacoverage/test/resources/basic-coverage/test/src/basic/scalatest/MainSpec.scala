package basic

import org.scalatest._

class MainSpec extends FlatSpec with Matchers {

  behavior of "Main"

  "runMethod" should "return 0 when 0 is given" in {
    Main.runMethod(0) shouldBe 0
  }

  it should "return 1 when 1 is given" in {
    Main.runMethod(1) shouldBe 1
  }
}