import mill._, scalalib._

trait MyModule extends ScalaModule{
  def scalaVersion = "2.13.2"
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")
}

object foo extends MyModule {
  def moduleDeps = Seq(bar)
}

object bar extends MyModule

/* Example Usage

> ./mill resolve __.run
foo.run
bar.run

> ./mill foo.run
Foo.value: <h1>hello</h1>
Bar.value: <p>world</p>

> ./mill bar.run
Bar.value: <p>world</p>

*/