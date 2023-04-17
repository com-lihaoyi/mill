import mill._, scalalib._

trait MyModule extends ScalaModule{
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::scalatags:0.8.2",
    ivy"com.lihaoyi::mainargs:0.4.0"
  )
}

object foo extends Module{
  object bar extends MyModule

  object qux extends MyModule {
    def moduleDeps = Seq(bar)
  }
}

object baz extends MyModule {
  def moduleDeps = Seq(foo.bar, foo.qux)
}

// Modules can be nested arbitrarily deeply within each other. The outer module
// can be the same kind of module as the ones within, or it can be a plain
// `Module` if we just need a wrapper to put the modules in without any tasks
// defined on the wrapper.
//
// Running tasks on the nested modules requires the full module path
// `wrapper.foo.run`

/* Example Usage

> ./mill resolve __.run
foo.bar.run
foo.qux.run
qux.run

> ./mill baz.run --bar-text hello --qux-text world --baz-text today
Bar.value: <h1>hello</h1>
Qux.value: <p>world</p>
Baz.value: <p>today</p>

> ./mill foo.qux.run --bar-text hello --qux-text world
Bar.value: <h1>hello</h1>
Qux.value: <p>world</p>

*/