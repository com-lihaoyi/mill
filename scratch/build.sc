import mill._
import mill.scalalib._

object root extends RootModule with ScalaModule {
  def scalaVersion = T { "2.13.10" }
}
