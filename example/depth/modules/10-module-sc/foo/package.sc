package foo
import mill._, scalalib._

object `package` extends RootModule with build.MyModule {
  def moduleDeps = Seq(bar.qux.mymodule)
  def ivyDeps = Agg(ivy"com.lihaoyi::mainargs:0.4.0")
}
