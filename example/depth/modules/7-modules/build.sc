// == Simple Modules
//
// The path to a Mill module from the root of your build file corresponds to the
// path you would use to run tasks within that module from the command line. e.g.
// for the following `build.sc`:

import mill._

object foo extends Module {
  def bar = T { "hello" }
  object qux extends Module {
    def baz = T { "world" }
  }
}

// [graphviz]
// ....
// digraph G {
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   "root-module" [style=dashed]
//   foo [style=dashed]
//   "foo.qux" [style=dashed]
//   "foo.qux.baz"
//   "root-module" -> foo -> "foo.qux" -> "foo.qux.baz"  [style=dashed]
//   foo -> "foo.bar"  [style=dashed]
// }
// ....
// You would be able to run the two targets via `mill foo.bar` or `mill
// foo.qux.baz`. You can use `mill show foo.bar` or `mill show foo.baz.qux` to
// make Mill echo out the string value being returned by each Target. The two
// targets will store their output metadata and files at
// `./out/foo/bar.{json,dest}` and `./out/foo/baz/qux.{json,dest}`
// respectively.

/** Usage

> ./mill foo.bar
> ./mill foo.qux.baz

> ./mill show foo.bar
"hello"

> ./mill show foo.qux.baz
"world"

> cat ./out/foo/bar.json # task output path follows module hierarchy
..."value": "hello"...

> cat ./out/foo/qux/baz.json
..."value": "world"...

*/

// == Trait Modules
//
// Modules also provide a way to define and re-use common collections of tasks,
// via Scala ``trait``s. Module ``trait``s support everything normal Scala
// ``trait``s do: abstract ``def``s, overrides, `super`, extension
// with additional ``def``s, etc.

trait FooModule extends Module {
  def bar: T[String] // required override
  def qux = T { bar() + " world" }
}

object foo1 extends FooModule{
  def bar = "hello"
  def qux = super.qux().toUpperCase // refer to overriden value via super
}
object foo2 extends FooModule {
  def bar = "hi"
  def baz = T { qux() + " I am Cow" } // add a new `def`
}

// This generates the following module tree and task graph, with the dotted boxes and
// arrows representing the module tree, and the solid boxes and arrows representing
// the task graph

// [graphviz]
// ....
// digraph G {
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   bgcolor=transparent
//   "root-module" [style=dashed]
//   foo1 [style=dashed]
//   foo2 [style=dashed]
//   "root-module" -> foo1 -> "foo1.bar"  [style=dashed]
//   foo1 -> "foo1.qux.super"  [style=dashed]
//   foo1 -> "foo1.qux"  [style=dashed]
//   "root-module" -> foo2 -> "foo2.bar"  [style=dashed]
//   foo2 -> "foo2.qux"  [style=dashed]
//   foo2 -> "foo2.baz"  [style=dashed]
//   "foo1.bar" -> "foo1.qux.super" -> "foo1.qux" [constraint=false]
//   "foo2.bar" -> "foo2.qux" -> "foo2.baz" [constraint=false]
// }
// ....

// Note that the `override` keyword is optional in mill, as is `T{...}` wrapper.

/** Usage

> ./mill show foo1.bar
"hello"

> ./mill show foo1.qux
"HELLO WORLD"

> ./mill show foo2.bar
"hi"

> ./mill show foo2.qux
"hi world"

> ./mill show foo2.baz
"hi world I am Cow"

*/


// The built-in `mill.scalalib` package uses this to define `ScalaModule`,
// `SbtModule` and `TestScalaModule`, etc. which contain a set of "standard"
// operations such as `compile`, `jar` or `assembly` that you may expect from a
// typical Scala module.
//
// When defining your own module abstractions, you should be using ``trait``s
// and not ``class``es due to implementation limitations
//
// == millSourcePath
//
// Each Module has a `millSourcePath` field that corresponds to the path that
// module expects its input files to be on disk.

trait MyModule extends Module{
  def sources = T.source(millSourcePath / "sources")
  def target = T { "hello " + os.list(sources().path).map(os.read(_)).mkString(" ") }
}

object outer extends MyModule {
  object inner extends MyModule
}

// [graphviz]
// ....
// digraph G {
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   "root-module" [style=dashed]
//   outer [style=dashed]
//
//   "outer.sources" -> "outer.target" [constraint=false]
//   "outer.inner.sources" -> "outer.inner.target" [constraint=false]
//   "outer.inner" [style=dashed]
//   "root-module" -> outer -> "outer.inner"  [style=dashed]
//   "outer.inner" -> "outer.inner.sources"  [style=dashed]
//   "outer.inner" -> "outer.inner.target"  [style=dashed]
//   outer -> "outer.sources"  [style=dashed]
//   outer -> "outer.target"  [style=dashed]
// }
// ....

// * The `outer` module has a `millSourcePath` of `outer/`, and thus a
//   `outer.sources` referencing `outer/sources/`
//
// * The `inner` module has a `millSourcePath` of `outer/inner/`, and thus a
//   `outer.inner.sources` referencing `outer/inner/sources/`

/** Usage

> ./mill show outer.target
"hello contents of file inside outer/sources/"

> ./mill show outer.inner.target
"hello contents of file inside outer/inner/sources/"

*/

// You can use `millSourcePath` to automatically set the source folders of your
// modules to match the build structure. You are not forced to rigidly use
// `millSourcePath` to define the source folders of all your code, but it can simplify
// the common case where you probably want your build-layout and on-disk-layout to
// be the same.
//
// E.g. for `mill.scalalib.ScalaModule`, the Scala source code is assumed by
// default to be in `millSourcePath / "src"` while resources are automatically
// assumed to be in `millSourcePath / "resources"`.
//
// You can override `millSourcePath`:

object outer2 extends MyModule {
  def millSourcePath = super.millSourcePath / "nested"
  object inner extends MyModule
}

/** Usage

> ./mill show outer2.target
"hello contents of file inside outer2/nested/sources/"

> ./mill show outer2.inner.target
"hello contents of file inside outer2/nested/inner/sources/"

*/

// Any overrides propagate down to the module's children: in the above example,
// `outer2` would have its `millSourcePath` be `outer2/nested/` while
// `outer.inner` would have its `millSourcePath` be `outer2/nested/inner/`.
//
// Note that `millSourcePath` is meant to be used for a module's input source
// files: source code, config files, library binaries, etc. Output is always in
// the `out/` folder and cannot be changed, e.g. even with the overridden
// `millSourcePath` the output paths are still the default `./out/outer2` and
// `./out/outer2/inner` folders:

/** Usage

> cat ./out/outer2/target.json
..."value": "hello contents of file inside outer2/nested/sources/"...

> cat ./out/outer2/inner/target.json
..."value": "hello contents of file inside outer2/nested/inner/sources/"...

*/

// *Note that `os.pwd` of the Mill process is set to an empty `sandbox/` folder by default.*
// When defining a module's source files, you should always use `millSourcePath` to ensure the
// paths defined are relative to the module's root folder, so the module logic can continue
// to work even if moved into a different subfolder. In the rare case where you need the
// Mill project root path, and you truly know what you are doing, you can call
// g`mill.api.WorkspaceRoot.workspaceRoot`.