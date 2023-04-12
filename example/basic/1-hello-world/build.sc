import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::scalatags:0.8.2",
    ivy"com.lihaoyi::mainargs:0.4.0"
  )
}

// This is a basic Mill build for a single `ScalaModule`, with a single
// third-party dependency. As a single-module project, it `extends BuildFileModule`
// to mark `object foo` as the top-level module in the build. This lets us
// directly perform operations `./mill compile` or `./mill run` without needing
// to prefix it as `foo.compile` or `foo.run`.
//
// This example project uses two third-party dependencies - MainArgs for CLI
// argument parsing, Scalatags for HTML generation - and uses them to wrap a
// given input string in HTML templates with proper escaping.
//
// You can run `assembly` to generate a standalone executable jar, which then
// can be run from the command line or deployed to be run elsewhere.

/* Example Usage

> ./mill compile
compiling 1 Scala source

> ./mill run
error: Missing argument: --text <str>

> ./mill run --text hello
<h1>hello</h1>

> ./mill show assembly
out/assembly.dest/out.jar

> java -jar ./out/assembly.dest/out.jar --text hello
<h1>hello</h1>

> ./out/assembly.dest/out.jar --text hello # mac/linux
<h1>hello</h1>

*/
