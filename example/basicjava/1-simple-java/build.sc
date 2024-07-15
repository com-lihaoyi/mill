import mill._, javalib._

object foo extends RootModule with JavaModule {
  def ivyDeps = Agg(
    ivy"net.sourceforge.argparse4j:argparse4j:0.9.0",
    ivy"org.apache.commons:commons-text:1.12.0"
  )

  object test extends JavaModuleTests with TestModule.Junit4{
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"com.google.guava:guava:33.2.1-jre"
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
// You can download this example project using the *download* link above
// if you want to try out the commands below yourself. The only requirement is
// that you have some version of the JVM installed; the `./mill` script takes
// care of any further dependencies that need to be downloaded.
//
// The source code for this module lives in the `src/` folder.
// Output for this module (compiled files, resolved dependency lists, ...)
// lives in `out/`.
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
compile(JavaModule.scala:...)
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
Test run finished: 0 failed, 0 ignored, 2 total, ...

> ./mill assembly # bundle classfiles and libraries into a jar for deployment

> ./mill show assembly # show the output of the assembly task
".../out/assembly.dest/out.jar"

> java -jar ./out/assembly.dest/out.jar --text hello
<h1>hello</h1>

> ./out/assembly.dest/out.jar --text hello # mac/linux
<h1>hello</h1>

*/

// The output of every Mill task is stored in the `out/` folder under a name
// corresponding to the task that created it. e.g. The `assembly` task puts its
// metadata output in `out/assembly.json`, and its output files in
// `out/assembly.dest`. You can also use `show` to make Mill print out the
// metadata output for a particular task.
//
// Additional Mill tasks you would likely need include:
//
// [source,bash]
// ----
// $ mill runBackground # run the main method in the background
//
// $ mill clean <task>  # delete the cached output of a task, terminate any runBackground
//
// $ mill launcher      # prepares a foo/launcher.dest/run you can run later
//
// $ mill jar           # bundle the classfiles into a jar suitable for publishing
//
// $ mill -i console    # start a Scala console within your project
//
// $ mill -i repl       # start an Ammonite Scala REPL within your project
// ----
//
// You can run `+mill resolve __+` to see a full list of the different tasks that
// are available, `+mill resolve _+` to see the tasks within `foo`,
// `mill inspect compile` to inspect a task's doc-comment documentation or what
// it depends on, or `mill show foo.scalaVersion` to show the output of any task.
//
// The most common *tasks* that Mill can run are cached *targets*, such as
// `compile`, and un-cached *commands* such as `foo.run`. Targets do not
// re-evaluate unless one of their inputs changes, whereas commands re-run every
// time.
