import mill._, scalalib._

object bar extends millbuild.MyModule {
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")
}
