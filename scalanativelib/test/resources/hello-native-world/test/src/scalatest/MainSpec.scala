package hellotest

import hello._
import org.scalatest._

class MainSpec extends FlatSpec with Matchers {

  behavior of "Main"

  "vmName" should "contain Native" in {
    Main.vmName should include ("Native")
  }

  it should "contain Scala" in {
    Main.vmName should include ("Scala")
  }

}
