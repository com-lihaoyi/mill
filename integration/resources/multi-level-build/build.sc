import mill._, scalalib._
import scalatags.Text.all._

object foo extends ScalaModule {
  def scalaVersion = "2.13.2"

  def forkEnv = Map(
    "snippet" -> frag(h1("hello"), p("world"), p(constant.Constant.scalatagsVersion)).render
  )
}

