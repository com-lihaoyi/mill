package build.bar.qux
import mill._, scalalib._

object mymodule extends build.MyModule {
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")
}
