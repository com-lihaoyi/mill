import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.12.8"
}

object bar extends ScalaModule {
  def scalaVersion = "2.12.8"
  override def moduleDeps = Seq(foo)
}
