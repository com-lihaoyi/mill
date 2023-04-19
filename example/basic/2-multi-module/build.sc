// == Multi-Module Project

import mill._, scalalib._

trait MyModule extends ScalaModule{
  def scalaVersion = "2.13.8"
}

object foo extends MyModule {
  def moduleDeps = Seq(bar)
  def ivyDeps = Agg(ivy"com.lihaoyi::mainargs:0.4.0")
}

object bar extends MyModule{
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")
}

// A simple Mill build with two modules, `foo` and `bar`. We don't mark either
// module as top-level using `extends BuildFileModule`, so running tasks needs to
// use the module name as the prefix e.g. `foo.run` or `bar.run`. You can define
// multiple modules the same way you define a single module, using
//`def moduleDeps` to define the relationship between them.
//
// The above builds expect the following project layout:
//
// ----
// build.sc
// foo/
//     src/
//         Main.scala
//     resources/
//         ...
// bar/
//     src/
//         Main2.scala
//     resources/
//         ...
// out/
//     foo/
//         ...
//     bar/
//         ...
// ----
//
// Note that we split out the `scalaVersion` configuration common to both
// modules into a separate `trait MyModule`. This lets us avoid the need to
// copy-paste common settings, while still letting us define any per-module
// `ivyDeps` configuration specific to a particular module.

/* Example Usage

> ./mill resolve __.run
foo.run
bar.run

> ./mill foo.run --foo-text hello --bar-text world
Foo.value: hello
Bar.value: <p>world</p>

> ./mill bar.run world
Bar.value: <p>world</p>

*/

// Mill's evaluator will ensure that the modules are compiled in the right
// order, and recompiled as necessary when source code in each module changes.
