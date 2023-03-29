import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.2"
  def moduleDeps = Seq(bar)
}

object bar extends ScalaModule {
  def scalaVersion = "2.13.2"
}