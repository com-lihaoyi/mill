import mill._, scalalib._

object `package` extends RootModule with build.util.MyModule{
  def forkEnv = Map(
    "MY_SCALA_VERSION" -> build.util.myScalaVersion,
    "MY_PROJECT_VERSION" -> build.foo.versions.myProjectVersion,
  )
}
///** See Also: util.sc */
///** See Also: foo/package.sc */
///** See Also: foo/versions.sc */


// Apart from having `package.sc` files in subfolders to define modules, Mill
// also allows you to have helper code in any `*.sc` file in the same folder
// as your `build.sc` or a `package.sc`.

/** Usage

> ./mill run
Main Env build.util.myScalaVersion: 2.13.14
Main Env build.foo.versions.myProjectVersion: 0.0.1

> ./mill foo.run
Foo Env build.util.myScalaVersion: 2.13.14
Foo Env build.foo.versions.myProjectVersion: 0.0.1

*/
