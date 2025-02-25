package build.foo
import mill._, scalalib._
object `package` extends RootModule with build.MyModule {
  def forkEnv = Map(
    "MY_SCALA_VERSION" -> build.myScalaVersion,
    "MY_PROJECT_VERSION" -> myProjectVersion
  )
}
