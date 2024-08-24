import mill._, scalalib._

trait MyModule extends ScalaModule {
  def scalaVersion = "2.13.11"
}

/** See Also: foo/module.sc */

/** See Also: bar/qux/module.sc */

// Mill allows you to break up your `build.sc` file into smaller files by defining the
// build-related logic for any particular subfolder as a `module.sc` file in that subfolder.
// This can be very useful to keep large Mill builds maintainable, as each folder's build logic
// gets co-located with the files that need to be built, and speeds up compilation of the
// build logic since each `build.sc` or `module.sc` file can be compiled independently when
// it is modified without re-compiling all the others.
//
//
// In this example, the root `build.sc` only contains the `trait MyModule`, but it is
// `foo/module.sc` and `bar/qux/module.sc` that define modules using it. The modules
// defined in `foo/module.sc` and `bar/qux/module.sc` are automatically nested within
// `foo` and `bar.qux` respectively, and can be referenced from the command line as below:

/** Usage

> ./mill resolve __
bar
...
bar.qux.module
...
bar.qux.module.compile
...
foo
...
foo.compile

> ./mill bar.qux.module.compile

> ./mill foo.compile

> ./mill foo.run --foo-text hello --bar-qux-text world
Foo.value: hello
BarQux.value: <p>world</p>
*/

// Note that in this example, `foo/module.sc` defines `object build extends RootModule`,
// and so the name `.build` does not need to be provided at the command line. In contrast,
// `bar/qux/module.sc` defines `object module` that does not extend `RootModule`, and so
// we need to explicitly reference it with a `.module` suffix.
//
// `module.sc` files are only discovered in direct subfolders of the root `build.sc` or
// subfolders of another folder containing a `module.sc`; Hence in this example, we need
// an `bar/module.sc` to be present for `bar/qux/module.sc` to be discovered, even
// though `bar/module.sc` is empty