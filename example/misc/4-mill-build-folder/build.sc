import mill._, scalalib._
import scalatags.Text.all._

object foo extends BuildModule with ScalaModule {
  def scalaVersion = millbuild.ScalaVersion.myScalaVersion
  def ivyDeps = Agg(ivy"com.lihaoyi::os-lib:0.9.1")

  def htmlSnippet = T{ h1("hello").toString }
  def resources = T.sources{
    os.write(T.dest / "snippet.txt", htmlSnippet())
    super.resources() ++ Seq(PathRef(T.dest))
  }

  def forkArgs = Seq(s"-Dmill.scalatags.version=${millbuild.DepVersions.scalatagsVersion}")
}

// This example illustrates usage of the `mill-build/` folder. Mill's `build.sc`
// file and it's `import $file` and `$ivy` are a shorthand syntax for defining
// a Mill `ScalaModule`, with sources and `ivyDeps` and so on, which is
// compiled and executed to perform your build. This module lives in
// `mill-build/`.
//
// In this example:
//
// 1. Our `myScalaVersion` value comes from `mill-build/src/Versions.scala`,
//    while the Scalatags library we use in `build.sc` comes from the
//    `def ivyDeps` in `mill-build/build.sc`.
//
// 2. We also use `generatedSources` in `mill-build/build.sc` to create a
//    `DepVersions` object that the `build.sc` can use to pass the
//    `scalatagsVersion` to the application without having to copy-paste the
//    version and keep the two copies in sync
//
// You can customize the `mill-build/` module with more flexibility than is
// provided by `import $ivy` or `import $file`, overriding any tasks that are
// present on a typical `ScalaModule`: `scalacOptions`, `generatedSources`, etc.
// This is useful for large projects where the build itself is a non-trivial
// module which requires its own non-trivial customization.

/* Example Usage

> ./mill compile
compiling 1 Scala source

> ./mill run
Foo.value: <h1>hello</h1>
scalatagsVersion: 0.8.2

> ./mill show assembly # mac/linux
out/assembly.dest/out.jar

> ./out/assembly.dest/out.jar # mac/linux
Foo.value: <h1>hello</h1>

> ./out/assembly.dest/out.bat # windows
Foo.value: <h1>hello</h1>
scalatagsVersion: 0.8.2

*/
