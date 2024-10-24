
import mill._
import scalalib._
import scalajslib._

object helloWorldWasm extends ScalaJSModule {
  def scalaVersion = "3.3.3"
  def scalaJSVersion = "1.17.0"
  
  def sources = T.sources {
    Seq(
      millSourcePath / "src" / "Main.scala"
    )
  }
}