// This is an example of cross-building a module across multiple Scala
// versions. Each module is replaced by a `Cross` module, which is given a list
// of strings you want the cross-module to be replicated for. You can then
// specify the cross-modules with square brackets when you want to run tasks on
// them.
//
// `CrossScalaModule`s support both shared sources within `src/` as well as
// version specific sources in `src-x/`, `src-x.y/`, or `src-x.y.z/` that
// apply to the cross-module with that version prefix.
//
// `CrossScalaModule` can depend on each other using `moduleDeps`, but require
// the `()` suffix in `moduleDeps` to select the appropriate instance of the
// cross-module to depend on.

import mill._, scalalib._

val scalaVersions = Seq("2.12.17", "2.13.10")

object foo extends Cross[FooModule](scalaVersions:_*)
class FooModule(val crossScalaVersion: String) extends CrossScalaModule

object bar extends Cross[FooModule](scalaVersions:_*)
class BarModule(val crossScalaVersion: String) extends CrossScalaModule{
  def moduleDeps = Seq(foo())
}

/* Example Usage

> ./mill resolve __.run
foo[2.12.17].run
foo[2.13.10].run
bar[2.12.17].run
bar[2.13.10].run

> ./mill foo[2.12.17].run
Foo.value: Hello World Scala library version 2.12.17
Specific code for Scala 2.x
Specific code for Scala 2.12.x

> ./mill foo[2.13.10].run
Foo.value: Hello World Scala library version 2.13.10
Specific code for Scala 2.x
Specific code for Scala 2.12.x

> ./mill bar[2.13.10].run
Bar.value: bar-value
Foo.value: Hello World Scala library version 2.13.10

*/