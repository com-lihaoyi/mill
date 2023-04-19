import mill._, scalalib._, publish._

trait MyModule extends PublishModule {
  def publishVersion = "0.0.1"

  def pomSettings = PomSettings(
    description = "Hello",
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/example",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "example"),
    developers = Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
  )
}

trait MyScalaModule extends  MyModule with CrossScalaModule{
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.12.0")
  object test extends Tests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")
    def testFramework = "utest.runner.Framework"
  }
}

val scalaVersions = Seq("2.13.8", "3.2.2")

object foo extends Cross[FooModule](scalaVersions)
trait FooModule extends MyScalaModule {
  def moduleDeps = Seq(bar(), qux)

  def generatedSources = T{
    os.write(
      T.dest / "Version.scala",
      s"""package foo
         |object Version{
         |  def value = "${publishVersion()}"
         |}
         |""".stripMargin
    )
    Seq(PathRef(T.dest))
  }
}

object bar extends Cross[BarModule](scalaVersions)
trait BarModule extends MyScalaModule {
  def moduleDeps = Seq(qux)
}

object qux extends JavaModule with MyModule

// A semi-realistic build setup, combining all the individual Mill concepts:
//
// - Two `CrossScalaModules` compiled against two Scala versions, that depend on
//   each other as well as on a `JavaModule`
//
// - With unit testing and publishing set up
//
// - With version-specific sources
//
// - With generated sources to include the `publishVersion` as a string in the
//   code, so it can be printed at runtime
//
// Note that for multi-module builds like this, using queries like `__.test`
// or `__.publishLocal` to run tasks on multiple targets at once can be very
// convenient. Also note that `ScalaModule`s can depend on `JavaModule`s, and
// when multiple inter-dependent modules are published they automatically will
// include the inter-module dependencies in the publish metadata.
//
// Also note how you can use `trait`s to bundle together common combinations of
// modules: `MyScalaModule` not only defines a `ScalaModule` with some common
// configuration, but it also defines a `object test` module within it with its
// own configuration. This is a very useful technique for managing the often
// repetitive module structure in a typical project

/* Example Usage

> ./mill resolve __.run
bar[2.13.8].run
bar[2.13.8].test.run
bar[3.2.2].run
bar[3.2.2].test.run
foo[2.13.8].run
foo[2.13.8].test.run
foo[3.2.2].run
foo[3.2.2].test.run
qux.run

> ./mill foo[2.13.8].run
foo version 0.0.1
Foo.value: <h1>hello</h1>
Bar.value: <p>world Specific code for Scala 2.x</p>
Qux.value: 31337

> ./mill bar[3.2.2].test
bar.BarTests.test
<p>world Specific code for Scala 3.x</p>

> ./mill qux.run
Qux.value: 31337

> ./mill __.compile

> ./mill __.test
+ bar.BarTests.test
<p>world Specific code for Scala 2.x</p>
+ bar.BarTests.test
<p>world Specific code for Scala 3.x</p>
+ foo.FooTests.test
<h1>hello</h1>
+ foo.FooTests.test
<h1>hello</h1>

> ./mill __.publishLocal
Publishing Artifact(com.lihaoyi,foo_2.13,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,bar_2.13,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,foo_3,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,bar_3,0.0.1) to ivy repo
Publishing Artifact(com.lihaoyi,qux,0.0.1) to ivy repo

> ./mill show foo[2.13.8].assembly # mac/linux
out/foo/2.13.8/assembly.dest/out.jar

> ./out/foo/2.13.8/assembly.dest/out.jar # mac/linux
foo version 0.0.1
Foo.value: <h1>hello</h1>
Bar.value: <p>world Specific code for Scala 2.x</p>
Qux.value: 31337

*/