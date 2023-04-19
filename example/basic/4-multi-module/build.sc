// == Multi-Module Project

import mill._, scalalib._

trait MyModule extends ScalaModule{
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::scalatags:0.8.2",
    ivy"com.lihaoyi::mainargs:0.4.0"
  )
}

object foo extends MyModule {
  def moduleDeps = Seq(bar)
}

object bar extends MyModule

// A simple Mill build with two modules, `foo` and `bar`. We don't mark either
// module as top-level using `extends BuildFileModule`, so running tasks needs to
// use the module name as the prefix e.g. `foo.run` or `bar.run`
//
// Note that we split out the configuration common to both modules into a
// separate `trait MyModule`. This lets us avoid the need to copy-paste common
// settings, while still letting us define any per-module configuration
// specific to a particular module e.g. overriding `moduleDeps` to make `foo`
// depend on `bar`

/* Example Usage

> ./mill resolve __.run
foo.run
bar.run

> ./mill foo.run --foo-text hello --bar-text world
Foo.value: <h1>hello</h1>
Bar.value: <p>world</p>

> ./mill bar.run --text world
Bar.value: <p>world</p>

*/