import mill._, scalalib._, publish._

trait MyModule extends PublishModule {
  def publishVersion = "0.0.1"

  def pomSettings = PomSettings(
    description = "Hello",
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/example",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "example"),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi")
    )
  )
}

trait MyScalaModule extends MyModule{
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.12.0")
  object test extends Tests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")

    def testFramework = "utest.runner.Framework"
  }
}

val scalaVersions = Seq("2.13.10", "3.2.2")

object foo extends Cross[FooModule](scalaVersions:_*)
class FooModule(val crossScalaVersion: String) extends MyScalaModule with CrossScalaModule{
  def moduleDeps = Seq(bar(), qux)
}

object bar extends Cross[BarModule](scalaVersions:_*)
class BarModule(val crossScalaVersion: String) extends MyScalaModule with CrossScalaModule{
  def moduleDeps = Seq(qux)
}

object qux extends MyModule with JavaModule