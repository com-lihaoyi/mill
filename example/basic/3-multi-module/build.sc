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

// This example contains a simple Mill build with two modules, `foo` and `bar`.
// We don't mark either module as top-level using `extends BuildFileModule`, so
// running tasks needs to use the module name as the prefix e.g. `foo.run` or
// `bar.run`. You can define multiple modules the same way you define a single
// module, using def moduleDeps` to define the relationship between them.
//
// Note that we split out the `scalaVersion` configuration common to both
// modules into a separate `trait MyModule`. This lets us avoid the need to
// copy-paste common settings, while still letting us define any per-module
// configuration such as `ivyDeps` specific to a particular module.
//
// The above builds expect the following project layout:
//
// ----
// build.sc
// foo/
//     src/
//         Foo.scala
//     resources/
//         ...
// bar/
//     src/
//         Bar.scala
//     resources/
//         ...
// out/
//     foo/
//         compile.json
//         compile.dest/
//         ...
//     bar/
//         compile.json
//         compile.dest/
//         ...
// ----
//
// Typically, both source code and output files in Mill follow the module
// hierarchy, so e.g. input to the `foo` module lives in `foo/src/` and
// compiled output files live in `out/foo/compile.dest`.

/** Usage

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
//
// You can use wildcards and brace-expansion to select
// multiple targets at once or to shorten the path to deeply nested targets. If
// you provide optional target arguments and your wildcard or brace-expansion is
// resolved to multiple targets, the arguments will be applied to each of the
// targets.
//
// .Wildcards and brace-expansion
// |==========================================================
// | Wildcard | Function
// | `_`      | matches a single segment of the target path
// | `__`     | matches arbitrary segments of the target path
// | `{a,b}`  | is equal to specifying two targets `a` and `b`
// |==========================================================
//
//
// You can use the `+` symbol to add another target with optional arguments.
// If you need to feed a `+` as argument to your target, you can mask it by
// preceding it with a backslash (`\`).
//
// === Examples
//
// `+mill foo._.compile+`:: Runs `compile` for all direct sub-modules of `foo`
// `+mill foo.__.test+` :: Runs `test` for all sub-modules of `foo`
// `+mill {foo,bar}.__.testCached+` :: Runs `testCached` for all sub-modules of `foo` and `bar`
// `+mill __.compile + foo.__.test+` :: Runs all `compile` targets and all tests under `foo`.
//
//