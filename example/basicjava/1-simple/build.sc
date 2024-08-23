//// SNIPPET:BUILD

import mill._, javalib._

object foo extends RootModule with JavaModule {
  def ivyDeps = Agg(
    ivy"net.sourceforge.argparse4j:argparse4j:0.9.0",
    ivy"org.apache.commons:commons-text:1.12.0"
  )

  object test extends JavaTests with TestModule.Junit4{
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"com.google.guava:guava:33.3.0-jre"
    )
  }
}

// This is a basic Mill build for a single `JavaModule`, with two
// third-party dependencies and a test suite using the JUnit framework. As a
// single-module project, it `extends RootModule` to mark `object foo` as the
// top-level module in the build. This lets us directly perform operations
// `./mill compile` or `./mill run` without needing to prefix it as
// `foo.compile` or `foo.run`.
//
//// SNIPPET:DEPENDENCIES
//
// This example project uses two third-party dependencies - ArgParse4J for CLI
// argument parsing, Apache Commons Text for HTML escaping - and uses them to wrap a
// given input string in HTML templates with proper escaping.
//
// You can run `assembly` to generate a standalone executable jar, which then
// can be run from the command line or deployed to be run elsewhere.

/** Usage

> ./mill resolve _ # List what tasks are available to run
assembly
...
clean
...
compile
...
run
...
show
...
inspect
...

> ./mill inspect compile # Show documentation and inputs of a task
compile(JavaModule...)
    Compiles the current module to generate compiled classfiles/bytecode.
Inputs:
    upstreamCompileOutput
    allSourceFiles
    compileClasspath
...

> ./mill compile # compile sources into classfiles
...
compiling 1 Java source to...

> ./mill run # run the main method, if any
error: argument -t/--text is required
...

> ./mill run --text hello
<h1>hello</h1>

> ./mill test
...
Test foo.FooTest.testEscaping finished, ...
Test foo.FooTest.testSimple finished, ...
Test run foo.FooTest finished: 0 failed, 0 ignored, 2 total, ...

> ./mill assembly # bundle classfiles and libraries into a jar for deployment

> ./mill show assembly # show the output of the assembly task
".../out/assembly.dest/out.jar"

> java -jar ./out/assembly.dest/out.jar --text hello
<h1>hello</h1>

> ./out/assembly.dest/out.jar --text hello # mac/linux
<h1>hello</h1>

*/
