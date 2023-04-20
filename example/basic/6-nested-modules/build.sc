// == Nesting Modules

import mill._, scalalib._

trait MyModule extends ScalaModule{
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::scalatags:0.8.2",
    ivy"com.lihaoyi::mainargs:0.4.0"
  )
}

object foo extends MyModule{
  def moduleDeps = Seq(bar, qux)

  object bar extends MyModule
  object qux extends MyModule {
    def moduleDeps = Seq(bar)
  }
}

object baz extends MyModule {
  def moduleDeps = Seq(foo.bar, foo.qux, foo)
}

// Modules can be nested arbitrarily deeply within each other. The outer module
// can be the same kind of module as the ones within, or it can be a plain
// `Module` if we just need a wrapper to put the modules in without any tasks
// defined on the wrapper.
//
// Running tasks on the nested modules requires the full module path
// `wrapper.foo.run`

/** Usage

> ./mill resolve __.run
foo.bar.run
foo.qux.run
qux.run

> ./mill foo.run --bar-text hello --qux-text world --foo-text today
Bar.value: <h1>hello</h1>
Qux.value: <p>world</p>
Foo.value: <p>today</p>

> ./mill baz.run --bar-text hello --qux-text world --foo-text today --baz-text yay
Bar.value: <h1>hello</h1>
Qux.value: <p>world</p>
Foo.value: <p>today</p>
Baz.value: <p>yay</p>

> ./mill foo.qux.run --bar-text hello --qux-text world
Bar.value: <h1>hello</h1>
Qux.value: <p>world</p>

*/