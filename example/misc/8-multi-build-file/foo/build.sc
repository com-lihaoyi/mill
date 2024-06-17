import mill._, scalalib._

object build extends RootModule with millbuild.MyModule {
  def moduleDeps = Seq(millbuild.bar.build)
  def ivyDeps = Agg(ivy"com.lihaoyi::mainargs:0.4.0")
}
