package build
import mill._, scalalib._

def myScalaVersion = "2.13.14"

trait MyModule extends ScalaModule {
  def scalaVersion = myScalaVersion
}
