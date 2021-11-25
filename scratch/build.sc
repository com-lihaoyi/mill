import mill._
import mill.scalalib._

object foo extends ScalaModule{
  def scalaVersion = "2.13.2"
  object bar extends ScalaModule{
    def scalaVersion = "2.13.2"
  }

  object qux extends ScalaModule{
    def scalaVersion = "2.13.2"
  }
}