package build
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
//
// Different helper scripts and ``build.sc``/``package.sc`` files can all refer to
// each other using the `build` object, which marks the root object of your build.
// In this example:
//
// * `build.sc` can be referred to as simple `build`
// * `util.sc` can be referred to as simple `build.util`
// * `foo/package.sc` can be referred to as simple `build.foo`
// * `foo/versions.sc` can be referred to as simple `build.foo.versions`
//
//

/** Usage

> ./mill run
Main Env build.util.myScalaVersion: 2.13.14
Main Env build.foo.versions.myProjectVersion: 0.0.1

> ./mill foo.run
Foo Env build.util.myScalaVersion: 2.13.14
Foo Env build.foo.versions.myProjectVersion: 0.0.1

*/
