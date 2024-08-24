import mill._, scalalib._, scalajslib._, publish._

trait Shared extends CrossScalaModule with PlatformScalaModule with PublishModule {
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
}

trait SharedTestModule extends TestModule {
  def ivyDeps = Agg(ivy"com.lihaoyi::utest::0.8.4")
  def testFramework = "utest.runner.Framework"
}

trait SharedJS extends Shared with ScalaJSModule {
  def scalaJSVersion = "1.16.0"
}

val scalaVersions = Seq("2.13.14", "3.3.3")

object bar extends Module {
  object jvm extends Cross[JvmModule](scalaVersions)
  trait JvmModule extends Shared {
    object test extends ScalaTests with SharedTestModule
  }

  object js extends Cross[JsModule](scalaVersions)
  trait JsModule extends SharedJS {
    object test extends ScalaJSTests with SharedTestModule
  }
}

object qux extends Module {
  object jvm extends Cross[JvmModule](scalaVersions)
  trait JvmModule extends Shared {
    def moduleDeps = Seq(bar.jvm())
    def ivyDeps = super.ivyDeps() ++ Agg(ivy"com.lihaoyi::upickle::3.0.0")

    object test extends ScalaTests with SharedTestModule
  }

  object js extends Cross[JsModule](scalaVersions)
  trait JsModule extends SharedJS {
    def moduleDeps = Seq(bar.js())

    object test extends ScalaJSTests with SharedTestModule
  }
}

// This example demonstrates an alternative way of defining your cross-platform
// cross-version modules: rather than wrapping them all in a `foo`
// cross-module to provide the different versions, we instead give each module
// `bar.jvm`, `bar.js`, `qux.jvm`, `qux.js` its own `Cross` module. This
// approach can be useful if the different cross modules need to support
// different sets of Scala versions, as it allows you to specify the
// `scalaVersions` passed to each individual cross module separately.

/** Usage

> ./mill show qux.js[3.3.3].sources
[
  ".../qux/src",
  ".../qux/src-js",
  ".../qux/src-3.3.3",
  ".../qux/src-3.3.3-js",
  ".../qux/src-3.3",
  ".../qux/src-3.3-js",
  ".../qux/src-3",
  ".../qux/src-3-js"
]

> ./mill show qux.js[3.3.3].test.sources
[
  ".../qux/test/src",
  ".../qux/test/src-js",
  ".../qux/test/src-3.3.3",
  ".../qux/test/src-3.3.3-js",
  ".../qux/test/src-3.3",
  ".../qux/test/src-3.3-js",
  ".../qux/test/src-3",
  ".../qux/test/src-3-js"
]

> ./mill qux.jvm[2.13.14].run
Bar.value: <p>world Specific code for Scala 2.x</p>
Parsing JSON with ujson.read
Qux.main: Set(<p>i</p>, <p>cow</p>, <p>me</p>)

> ./mill __.js[3.3.3].test
+ bar.BarTests.test ...  <p>world Specific code for Scala 3.x</p>
+ qux.QuxTests.parseJsonGetKeys ...  Set(i, cow, me)

> ./mill __.publishLocal
...
Publishing Artifact(com.lihaoyi,bar_sjs1_2.13,0.0.1) to ivy repo...
Publishing Artifact(com.lihaoyi,bar_2.13,0.0.1) to ivy repo...
Publishing Artifact(com.lihaoyi,qux_sjs1_2.13,0.0.1) to ivy repo...
Publishing Artifact(com.lihaoyi,qux_2.13,0.0.1) to ivy repo...
Publishing Artifact(com.lihaoyi,bar_sjs1_3,0.0.1) to ivy repo...
Publishing Artifact(com.lihaoyi,bar_3,0.0.1) to ivy repo...
Publishing Artifact(com.lihaoyi,qux_sjs1_3,0.0.1) to ivy repo...
Publishing Artifact(com.lihaoyi,qux_3,0.0.1) to ivy repo...

*/
