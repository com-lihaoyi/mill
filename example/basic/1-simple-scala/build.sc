// == Simple Scala Module

import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::scalatags:0.8.2",
    ivy"com.lihaoyi::mainargs:0.4.0"
  )
}

// This is a basic Mill build for a single `ScalaModule`, with a single
// third-party dependency. As a single-module project, it `extends RootModule`
// to mark `object foo` as the top-level module in the build. This lets us
// directly perform operations `./mill compile` or `./mill run` without needing
// to prefix it as `foo.compile` or `foo.run`.
//
// The source code for this module would live in the `src/` folder.
// Output for this module (compiled files, resolved dependency lists, …) would
// live in `out/`.
//
// This example project uses two third-party dependencies - MainArgs for CLI
// argument parsing, Scalatags for HTML generation - and uses them to wrap a
// given input string in HTML templates with proper escaping.
//
// You can run `assembly` to generate a standalone executable jar, which then
// can be run from the command line or deployed to be run elsewhere.

/** Example Usage

> ./mill resolve _ # List what tasks are available to run
compile
run
assembly
show
inspect
clean

> ./mill inspect compile # Show documentation and inputs of a task
compile(ScalaModule.scala:...)
Compiles the current module to generate compiled classfiles/bytecode.
Inputs:
    scalaVersion
    upstreamCompileOutput
    allSourceFiles
    compileClasspath

> ./mill compile # compile sources into classfiles
compiling 1 Scala source

> ./mill run # run the main method, if any
error: Missing argument: --text <str>

> ./mill run --text hello
<h1>hello</h1>

> ./mill assembly # bundle classfiles and libraries into a jar for deployment

> ./mill show assembly # show the output of the assembly task
out/assembly.dest/out.jar

> java -jar ./out/assembly.dest/out.jar --text hello
<h1>hello</h1>

> ./out/assembly.dest/out.jar --text hello # mac/linux
<h1>hello</h1>

*/
