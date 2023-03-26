// build.sc
import mill._, scalalib._
pprint.log(millSourcePath)
object foo extends ScalaModule {
  pprint.log(millSourcePath)
  def scalaVersion = "2.13.2"
}

