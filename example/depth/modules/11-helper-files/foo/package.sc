package foo
import mill._, scalalib._

object `package` extends RootModule with build.util.MyModule {
  def forkEnv = Map(
    "MY_SCALA_VERSION" -> build.util.myScalaVersion,
    "MY_PROJECT_VERSION" -> build.foo.versions.myProjectVersion
  )
}
