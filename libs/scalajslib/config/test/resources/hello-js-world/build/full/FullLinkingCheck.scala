import scala.scalajs.LinkingInfo

object FullLinkingCheck {
  def main(args: Array[String]): Unit = {
    println(s"developmentMode=${LinkingInfo.developmentMode}")
    assert(!LinkingInfo.developmentMode, "full linking expected")
  }
}
