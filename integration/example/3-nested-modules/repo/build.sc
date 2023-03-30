import mill._, scalalib._

trait MyModule extends ScalaModule{
  def scalaVersion = "2.13.2"
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")
}

object wrapper extends Module{
  object foo extends MyModule {
    def moduleDeps = Seq(bar)
  }

  object bar extends MyModule
}

object qux extends MyModule {
  def moduleDeps = Seq(wrapper.bar, wrapper.foo)
}