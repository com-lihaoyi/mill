import mill._, scalalib._

object module extends build.MyModule {
  def moduleDeps = Seq(build.bar.qux.mymodule)
  def ivyDeps = Agg(ivy"com.lihaoyi::mainargs:0.4.0")
}
