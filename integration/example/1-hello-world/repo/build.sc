import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.2"
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")
}
