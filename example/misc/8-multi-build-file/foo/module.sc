import mill._, scalalib._

object build extends RootModule with MyModule {
  def moduleDeps = Seq(bar.qux.module)
  def ivyDeps = Agg(ivy"com.lihaoyi::mainargs:0.4.0")
}
