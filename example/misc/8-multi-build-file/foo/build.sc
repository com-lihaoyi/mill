import mill._, scalalib._

object foo extends RootModule with millbuild.MyModule {
  def moduleDeps = Seq(millbuild.bar.bar)
  def ivyDeps = Agg(ivy"com.lihaoyi::mainargs:0.4.0")
}
