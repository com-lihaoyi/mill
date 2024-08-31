import mill._, scalalib._

trait MyModule extends ScalaModule {
  def scalaVersion = util.scalaVersion
}

object build extends MyModule{
  def forkEnv = Map(
    "SCALA_VERSION" -> build.util.scalaVersion,
    "SCALA_VERSION2" -> util.scalaVersion,
    "PROJECT_VERSION" -> build.foo.versions.projectVersion,
    "PROJECT_VERSION2" -> foo.versions.projectVersion
  )
}
/** See Also: util.sc */
/** See Also: foo/package.sc */
/** See Also: foo/versions.sc */


// Apart from having `package.sc` files in subfolders to define modules, Mill
// also allows you to have helper code in any `*.sc` file in the same folder
// as your `build.sc` or a `package.sc`.

/** Usage

> ./mill resolve __
bar
...
bar.qux.mymodule
...
bar.qux.mymodule.compile
...
foo
...
foo.compile

> ./mill bar.qux.mymodule.compile

> ./mill foo.compile

> ./mill foo.run --foo-text hello --bar-qux-text world
Foo.value: hello
BarQux.value: <p>world</p>
*/
