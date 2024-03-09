// Issue https://github.com/com-lihaoyi/mill/issues/3068
import mill._
import mill.scalalib._
import mill.scalajslib._
import mill.scalanativelib._

object app extends Module {
  trait Common extends ScalaModule {
    def millSourcePath = super.millSourcePath / os.up
    def scalaVersion = "3.4.0"
  }

  object jvm extends Common
  object js extends Common with ScalaJSModule {
    def scalaJSVersion = "1.15.0"
  }
  object native extends Common with ScalaNativeModule {
    def scalaNativeVersion = "0.4.17"
  }
}
