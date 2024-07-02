import mill._, scalalib._

object module extends build.MyModule {
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")
}
