// == Simple Modules
//
// The path to a Mill module from the root of your build file corresponds to the
// path you would use to run tasks within that module from the command line. e.g.
// for the following `build.sc`:

import mill._

object foo extends mill.Module {
  def bar = T { "hello" }
  object qux extends mill.Module {
    def baz = T { "world" }
  }
}

// You would be able to run the two targets via `mill foo.bar` or `mill
// foo.baz.qux`. You can use `mill show foo.bar` or `mill show foo.baz.qux` to
// make Mill echo out the string value being returned by each Target. The two
// targets will store their output metadata & files at `./out/foo/bar` and
// `./out/foo/baz/qux` respectively.

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

// Modules also provide a way to define and re-use common collections of tasks,
// via Scala ``trait``s. For example, you can define your own `FooModule`
// trait, and use it to instantiate modules with added customizations or
// overrides:

trait FooModule extends mill.Module {
  def bar = T { "hello" }
  def qux = T { bar() + "world" }
}

object foo1 extends FooModule{
  // `override` keyword is implicit in mill, as is `T{...}` wrapper
  def bar = super.bar().toUpperCase
}
object foo2 extends FooModule {
  def baz = T { qux() + " I am Cow" }
}

/** Usage

> ./mill show foo1.bar
"HELLO"

> ./mill show foo1.qux
"HELLOworld"

> ./mill show foo2.bar
"hello"

> ./mill show foo2.qux
"helloworld"

> ./mill show foo2.baz
"helloworld I am Cow"

*/


// The built-in `mill.scalalib` package uses this to define `ScalaModule`,
// `SbtModule` and `TestScalaModule`, etc. which contain a set of "standard"
// operations such as `compile`, `jar` or `assembly` that you may expect from a
// typical Scala module.
//
// When defining your own module abstractions, you should be using ``trait``s
// and not ``class``es due to implementation limitations

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
