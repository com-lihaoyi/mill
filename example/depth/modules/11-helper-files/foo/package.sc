package foo
import mill._, scalalib._

object `package` extends RootModule with build.MyModule {
  def forkEnv = Map(
    "SCALA_VERSION" -> build.util.scalaVersion,
    "SCALA_VERSION2" -> util.scalaVersion,
    "PROJECT_VERSION" -> build.foo.versions.projectVersion,
    "PROJECT_VERSION2" -> versions.projectVersion
  )
}
