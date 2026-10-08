import scala.scalajs.LinkingInfo

object FastLinkingCheck {
  def main(args: Array[String]): Unit = {
    println(s"developmentMode=${LinkingInfo.developmentMode}")
    assert(LinkingInfo.developmentMode, "fast linking expected")
  }
}
