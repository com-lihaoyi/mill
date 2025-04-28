import org.scalatest._

class MainSpec extends FlatSpec with Matchers {

  behavior of "Main"

  "vmName" should "contain js" in {
    Main.vmName should include("js")
  }

  it should "contain Scala" in {
    Main.vmName should include("Scala")
  }

}
