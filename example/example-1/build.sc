// build.sc
import mill._, scalalib._
import $file.{bar, qux}
import $ivy.`com.lihaoyi::scalatags:0.12.0`, scalatags.Text.all._
object foo extends ScalaModule {
  def scalaVersion = bar.myScalaVersion

  override def forkEnv = Map("snippet" -> h1(qux.myMsg).toString)
}

