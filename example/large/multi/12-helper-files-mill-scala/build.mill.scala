package build
import $packages._
import mill._, scalalib._

object `package` extends RootModule with MyModule {
  def forkEnv = Map(
    "MY_SCALA_VERSION" -> build.scalaVersion(),
    "MY_PROJECT_VERSION" -> build.foo.myProjectVersion
  )
}
///** See Also: util.mill.scala */
///** See Also: foo/package.mill.scala */
///** See Also: foo/versions.mill.scala */

// Apart from having `package` files in subfolders to define modules, Mill
// also allows you to have helper code in any `*.mill` file in the same folder
// as your `build.mill` or a `package.mill`.
//
// Different helper scripts and ``build.mill``/``package`` files can all refer to
// each other using the `build` object, which marks the root object of your build.
// In this example:
//
// * `build.mill` can be referred to as simple `build`
// * `util.mill` can be referred to as simple `$file.util`
// * `foo/package` can be referred to as simple `build.foo`
// * `foo/versions.mill` can be referred to as simple `$file.foo.versions`

/** Usage

> ./mill run
Main Env build.util.myScalaVersion: 2.13.14
Main Env build.foo.versions.myProjectVersion: 0.0.1

> ./mill foo.run
Foo Env build.util.myScalaVersion: 2.13.14
Foo Env build.foo.versions.myProjectVersion: 0.0.1

*/
