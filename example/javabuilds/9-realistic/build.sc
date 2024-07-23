//// SNIPPET:ALL
import mill._, javalib._, publish._

trait MyModule extends JavaModule with PublishModule {
  def publishVersion = "0.0.1"

  def pomSettings = PomSettings(
    description = "Hello",
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/example",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "example"),
    developers = Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
  )

  def ivyDeps = Agg(ivy"org.apache.commons:commons-text:1.12.0")

  object test extends JavaTests with TestModule.Junit4
}

object foo extends MyModule {
  def moduleDeps = Seq(bar, qux)

  def generatedSources = T {
    os.write(
      T.dest / "Version.java",
      s"""
         |package foo;
         |public class Version {
         |    public static String value() {
         |        return "${publishVersion()}";
         |    }
         |}
      """.stripMargin
    )
    Seq(PathRef(T.dest))
  }
}

object bar extends MyModule {
  def moduleDeps = Seq(qux)
}

object qux extends MyModule

// A semi-realistic build setup, combining all the individual Mill concepts:
//
// - Three `JavaModule`s that depend on each other
//
// - With unit testing and publishing set up
//
// - With generated sources to include the `publishVersion` as a string in the
//   code, so it can be printed at runtime
//
// Note that for multi-module builds like this, using queries to run tasks on
// multiple targets at once can be very convenient:
//
// ----
// __.test
// __.publishLocal
// ----
//
// Also note how you can use ``trait``s to bundle together common combinations of
// modules: `My=Module` not only defines a `JavaModule` with some common
// configuration, but it also defines a `object test` module within it with its
// own configuration. This is a very useful technique for managing the often
// repetitive module structure in a typical project

/** Usage

> mill resolve __.run
bar.run
bar.test.run
foo.run
foo.test.run
qux.run

> mill foo.run
foo version 0.0.1
Foo.value: <h1>hello</h1>
Bar.value: <p>world</p>
Qux.value: 31337

> mill bar.test
...bar.BarTests.test ...

> mill qux.run
Qux.value: 31337

> mill __.compile

> mill __.test
...bar.BarTests.test ...
...foo.FooTests.test ...

> mill __.publishLocal
Publishing Artifact(com.lihaoyi,foo,0.0.1) to ivy repo...
Publishing Artifact(com.lihaoyi,bar,0.0.1) to ivy repo...
Publishing Artifact(com.lihaoyi,qux,0.0.1) to ivy repo...
...

> mill show foo.assembly # mac/linux
".../out/foo/assembly.dest/out.jar"

> ./out/foo/assembly.dest/out.jar # mac/linux
foo version 0.0.1
Foo.value: <h1>hello</h1>
Bar.value: <p>world</p>
Qux.value: 31337

*/