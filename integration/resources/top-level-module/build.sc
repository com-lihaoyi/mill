// build.sc
import mill._
import scalalib._

object foo extends runner.BaseModule with ScalaModule {
  def scalaVersion = "2.13.2"

}

//def run() = T.command{ println("Hello World") }
