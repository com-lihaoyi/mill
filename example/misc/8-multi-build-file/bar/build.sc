import mill._, scalalib._

object build extends millbuild.MyModule {
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")
}
