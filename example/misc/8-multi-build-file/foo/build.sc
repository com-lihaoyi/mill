import mill._, scalalib._

object foo extends millbuild.build.MyModule with RootModule{
  def moduleDeps = Seq(millbuild.bar.build.bar)
  def ivyDeps = Agg(ivy"com.lihaoyi::mainargs:0.4.0")
}
