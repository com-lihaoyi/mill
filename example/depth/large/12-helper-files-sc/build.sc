// To ease the migration from Mill 0.11.x, the older `.sc` file extension is also supported
// for Mill build files, and the `package` declaration is optional in such files. Note that
// this means that IDE support using `.sc` files will not be as good as IDE support using the
// current `.mill` extension with `package` declaration, so you should use `.mill` whenever
// possible


import mill._, scalalib._
import $packages._
import $file.foo.versions
import $file.util, util.MyModule

object `package` extends RootModule with MyModule{
  def forkEnv = T{
    Map(
      "MY_SCALA_VERSION" -> build.scalaVersion(),
      "MY_PROJECT_VERSION" -> versions.myProjectVersion,
    )
  }
}
/** See Also: util.sc */
/** See Also: foo/package.sc */
/** See Also: foo/versions.sc */



/** Usage

> ./mill run
Main Env build.util.myScalaVersion: 2.13.14
Main Env build.foo.versions.myProjectVersion: 0.0.1

> ./mill foo.run
Foo Env build.util.myScalaVersion: 2.13.14
Foo Env build.foo.versions.myProjectVersion: 0.0.1

*/
