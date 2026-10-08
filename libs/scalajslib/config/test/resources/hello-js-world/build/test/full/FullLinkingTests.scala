import scala.scalajs.LinkingInfo
import utest._

object FullLinkingTests extends TestSuite {
  val tests = Tests {
    test {
      Predef.assert(!LinkingInfo.developmentMode, "full linking expected")
    }
  }
}
