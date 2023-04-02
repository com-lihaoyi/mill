import $file.{bar, qux}
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = bar.myScalaVersion
}

