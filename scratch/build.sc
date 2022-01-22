import mill._
import mill.scalalib._

object foo extends ScalaModule{
  def scalaVersion = "2.13.2"
  def packagePrefix: Map[String, String] = Map(
    "src" -> "foo",
  )
}
