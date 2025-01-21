package build.foo
import mill._, scalalib._
import $file.util
import $file.foo.versions.myProjectVersion
object `package` extends RootModule with build_.util.MyModule {
  def forkEnv = Map(
    "MY_SCALA_VERSION" -> util.myScalaVersion,
    "MY_PROJECT_VERSION" -> myProjectVersion
  )
}
