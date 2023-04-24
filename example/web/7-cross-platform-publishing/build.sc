import mill._, scalalib._, scalajslib._, publish._

object foo extends Cross[FooModule]("2.13.8", "3.2.2")
trait FooModule extends Cross.Module[String] {
  trait Shared extends CrossScalaModule with InnerCrossModule with PlatformScalaModule with PublishModule {
    def publishVersion = "0.0.1"

    def pomSettings = PomSettings(
      description = "Hello",
      organization = "com.lihaoyi",
      url = "https://github.com/lihaoyi/example",
      licenses = Seq(License.MIT),
      versionControl = VersionControl.github("lihaoyi", "example"),
      developers = Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
    )

    def ivyDeps = Agg(ivy"com.lihaoyi::scalatags::0.12.0")

    object test extends Tests {
      def ivyDeps = Agg(ivy"com.lihaoyi::utest::0.7.11")
      def testFramework = "utest.runner.Framework"
    }
  }

  trait SharedJS extends Shared with ScalaJSModule {
    def scalaJSVersion = "1.13.0"
  }

  object bar extends Module {
    object jvm extends Shared

    object js extends SharedJS
  }

  object qux extends Module{
    object jvm extends Shared{
      def moduleDeps = Seq(bar.jvm)
      def ivyDeps = super.ivyDeps() ++ Agg(ivy"com.lihaoyi::upickle::3.0.0")
    }
    object js extends SharedJS {
      def moduleDeps = Seq(bar.js)
    }
  }

}

// This example demonstrates how to publish Scala modules which are both
// cross-version and cross-platform: running on both Scala 2.13.8/3.2.2 as
// well as Scala-JVM/JS.

/* Example Usage

> ./mill show foo[2.13.8].bar.jvm.sources
foo/bar/src
foo/bar/src-jvm
foo/bar/src-2.13.8
foo/bar/src-2.13.8-jvm
foo/bar/src-2.13
foo/bar/src-2.13-jvm
foo/bar/src-2
foo/bar/src-2-jvm

> ./mill show foo[3.2.2].qux.js.sources
foo/qux/src
foo/qux/src-js
foo/qux/src-3.2.2
foo/qux/src-3.2.2-js
foo/qux/src-3.2
foo/qux/src-3.2-js
foo/qux/src-3
foo/qux/src-3-js

> ./mill foo[2.13.8].qux.jvm.run
Bar.value: <p>world Specific code for Scala 2.x</p>
Parsing JSON with ujson.read
Qux.main: Set(<p>i</p>, <p>cow</p>, <p>me</p>)

> ./mill foo[3.2.2].qux.js.run
Bar.value: <p>world Specific code for Scala 3.x</p>
Parsing JSON with js.JSON.parse
Qux.main: Set(<p>i</p>, <p>cow</p>, <p>me</p>)

> ./mill __.publishLocal
Publishing Artifact(com.lihaoyi,foo-bar_sjs1_2.13,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,foo-bar_2.13,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,foo-qux_sjs1_2.13,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,foo-qux_2.13,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,foo-bar_sjs1_3,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,foo-bar_3,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,foo-qux_sjs1_3,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,foo-qux_3,0.0.1) to ivy repo
*/
