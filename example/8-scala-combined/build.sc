// A semi-realistic build setup, combining all the individual Mill concepts:
// two `CrossScalaModules` compiled against two Scala versions, that depend on
// each other as well as on a `JavaModule`, with unit testing and publishing
// set up.
//
//
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

trait MyScalaModule extends ScalaModule with MyModule {
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

object qux extends JavaModule with MyModule

/* Example Usage

> ./mill resolve __.run
bar[2.13.10].run
bar[2.13.10].test.run
bar[3.2.2].run
bar[3.2.2].test.run
foo[2.13.10].run
foo[2.13.10].test.run
foo[3.2.2].run
foo[3.2.2].test.run
qux.run

> ./mill foo[2.13.10].run
Foo.value: <h1>hello</h1>
Bar.value: <p>world Specific code for Scala 2.x</p>
Qux.value: 31337

> ./mill bar[3.2.2].test
bar.BarTests.test
<p>world Specific code for Scala 3.x</p>

> ./mill qux.run
Qux.value: 31337

*/