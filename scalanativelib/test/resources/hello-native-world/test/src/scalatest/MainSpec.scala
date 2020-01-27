package hellotest

import hello._
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AnyFlatSpec

class MainSpec extends AnyFlatSpec with Matchers {

  behavior of "Main"

  "vmName" should "contain Native" in {
    Main.vmName should include ("Native")
  }

  it should "contain Scala" in {
    Main.vmName should include ("Scala")
  }

}
