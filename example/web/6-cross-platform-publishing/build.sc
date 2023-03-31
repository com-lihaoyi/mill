import mill._, scalalib._, scalajslib._, publish._

object wrapper extends Cross[WrapperModule]("2.13.10", "3.2.2")
class WrapperModule(val crossScalaVersion: String) extends Module {

  trait MyModule extends CrossScalaModule with PublishModule {
    def artifactName = millModuleSegments.parts.dropRight(1).last

    def crossScalaVersion = WrapperModule.this.crossScalaVersion
    def millSourcePath = super.millSourcePath / os.up
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

    def sources = T.sources {
      val platform = millModuleSegments.parts.last
      super.sources().flatMap(source =>
        Seq(
          source,
          PathRef(source.path / os.up / s"${source.path.last}-${platform}")
        )
      )
    }
  }
  trait MyScalaJSModule extends MyModule with ScalaJSModule {
    def scalaJSVersion = "1.13.0"
  }

  object foo extends Module{
    object jvm extends MyModule{
      def moduleDeps = Seq(bar.jvm)
      def ivyDeps = super.ivyDeps() ++ Agg(ivy"com.lihaoyi::upickle::3.0.0")
    }
    object js extends MyScalaJSModule {
      def moduleDeps = Seq(bar.js)
    }
  }

  object bar extends Module{
    object jvm extends MyModule
    object js extends MyScalaJSModule
  }
}

// This example demonstrates how to publish Scala modules which are both
// cross-version and cross-platform: running on both Scala 2.13.10/3.2.2 as
// well as Scala-JVM/JS.

/* Example Usage

> ./mill show wrapper[2.13.10].foo.jvm.sources
wrapper/foo/src
wrapper/foo/src-jvm
wrapper/foo/src-2.13.10
wrapper/foo/src-2.13.10-jvm
wrapper/foo/src-2.13
wrapper/foo/src-2.13-jvm
wrapper/foo/src-2
wrapper/foo/src-2-jvm

> ./mill show wrapper[3.2.2].bar.js.sources
wrapper/bar/src
wrapper/bar/src-js
wrapper/bar/src-3.2.2
wrapper/bar/src-3.2.2-v
wrapper/bar/src-3.2
wrapper/bar/src-3.2-js
wrapper/bar/src-3
wrapper/bar/src-3-js

> ./mill wrapper[2.13.10].foo.jvm.run
Bar.value: <p>world Specific code for Scala 2.x</p>
Parsing JSON with ujson.read
Foo.main: Set(<p>i</p>, <p>cow</p>, <p>me</p>)

> ./mill wrapper[3.2.2].foo.js.run
Bar.value: <p>world Specific code for Scala 3.x</p>
Parsing JSON with js.JSON.parse
Foo.main: Set(<p>i</p>, <p>cow</p>, <p>me</p>)

> ./mill __.publishLocal
Publishing Artifact(com.lihaoyi,bar_sjs1_2.13,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,bar_2.13,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,foo_sjs1_2.13,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,foo_2.13,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,bar_sjs1_3,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,bar_3,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,foo_sjs1_3,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,foo_3,0.0.1) to ivy repo
*/