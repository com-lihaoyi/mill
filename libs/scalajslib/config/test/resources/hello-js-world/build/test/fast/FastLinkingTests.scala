import scala.scalajs.LinkingInfo
import utest._

object FastLinkingTests extends TestSuite {
  val tests = Tests {
    test {
      Predef.assert(LinkingInfo.developmentMode, "fast linking expected")
    }
  }
}
