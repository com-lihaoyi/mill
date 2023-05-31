// The following examples will be assuming the `build.sc` file given below:

import mill._, scalalib._

trait MyModule extends ScalaModule {
  def scalaVersion = "2.13.8"
}

object foo extends MyModule {
  def moduleDeps = Seq(bar)
  def ivyDeps = Agg(ivy"com.lihaoyi::mainargs:0.4.0")
}

object bar extends MyModule {
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags:0.8.2")
}

// == resolve

/** Usage

> ./mill resolve _
foo
bar
clean
inspect
path
plan
resolve
show
shutdown
version
visualize
visualizePlan

> ./mill resolve _.compile
foo.compile

> ./mill resolve foo._
foo.allSourceFiles
foo.allSources
foo.ammoniteReplClasspath
foo.ammoniteVersion
foo.artifactId
foo.artifactName
...

*/

// `resolve` lists the tasks that match a particular query, without running them.
// This is useful for "dry running" an `mill` command to see what would be run
// before you run them, or to explore what modules or tasks are available
// from the command line using `+resolve _+`, `+resolve foo._+`, etc.

/** Usage

> ./mill resolve foo.{compile,run}
> ./mill resolve "foo.{compile,run}"
> ./mill resolve foo.compile foo.run
> ./mill resolve _.compile  # list the compile tasks for every top-level module
> ./mill resolve __.compile # list the compile tasks for every module
> ./mill resolve _          # list every top level module and task
> ./mill resolve foo._      # list every task directly within the foo module
> ./mill resolve __         # list every module and task recursively

*/

// == inspect

/** Usage

> ./mill inspect foo.run
foo.run(JavaModule.scala:...)
    Runs this module's code in a subprocess and waits for it to finish
Inputs:
    foo.finalMainClass
    foo.runClasspath
    foo.forkArgs
    foo.forkWorkingDir
    foo.runUseArgsFile

*/

// `inspect` is a more verbose version of <<_resolve>>. In addition to printing
// out the name of one-or-more tasks, it also displays its source location and a
// list of input tasks. This is very useful for debugging and interactively
// exploring the structure of your build from the command line.
//
// While `inspect` also works with the same `+_+`/`+__+` wildcard/query syntaxes
// that <<_resolve>> do, the most common use case is to inspect one task at a
// time.

// == show

/** Usage

> ./mill show foo.scalaVersion
"2.13.8"

*/

// By default, Mill does not print out the metadata from evaluating a task. Most
// people would not be interested in e.g. viewing the metadata related to
// incremental compilation: they just want to compile their code! However, if you
// want to inspect the build to debug problems, you can make Mill show you the
// metadata output for a task using the `show` command:
//
// `show` is not just for showing configuration values. All tasks return values
// that can be shown with `show`. E.g. `compile` returns the paths to the
// `classes` folder and `analysisFile` file produced by the compilation:

/** Usage

> ./mill show foo.compile
{
  "analysisFile": ".../out/foo/compile.dest/zinc",
  "classes": ".../out/foo/compile.dest/classes"
}
*/

// `show` is generally useful as a debugging tool, to see what is going on in your build:

/** Usage

> ./mill show foo.sources
[
  ".../foo/src"
]

> ./mill show foo.compileClasspath
[
  ".../foo/compile-resources",
  ".../org/scala-lang/scala-library/2.13.8/scala-library-2.13.8.jar",
  ...
]

*/

// `show` is also useful for interacting with Mill from external tools, since the
// JSON it outputs is structured and easily parsed and manipulated.
//
// When `show` is used with multiple targets, its output will slightly change to a
// JSON array, containing all the results of the given targets.

/** Usage

> ./mill show "foo.{sources,compileClasspath}"
{
  "foo.sources": [
    ".../foo/src"
  ],
  "foo.compileClasspath": [
    ".../foo/compile-resources",
    ".../org/scala-lang/scala-library/2.13.8/scala-library-2.13.8.jar",
    ...
  ]
}

*/

// == showNamed

// Same as `show`, but the output will always be structured in a JSON
// dictionary, whether there is one or more targets in the selection

/** Usage

> ./mill showNamed "foo.{sources,compileClasspath}"
{
  "foo.sources": [
    ".../foo/src"
  ],
  "foo.compileClasspath": [
    ".../foo/compile-resources",
    ".../org/scala-lang/scala-library/2.13.8/scala-library-2.13.8.jar",
    ...
  ]
}

*/

// == path

/** Usage

> ./mill path foo.assembly foo.sources
foo.sources
foo.allSources
foo.allSourceFiles
foo.compile
foo.localClasspath
foo.assembly

*/

// `mill path` prints out a dependency chain between the first task and the
// second. It is very useful for exploring the build graph and trying to figure
// out how data gets from one task to another, or trying to figure out why
// running `./mill foo` ends up running another task `bar` that you didn't
// expect it to.
//
// If there are multiple possible dependency chains, one of them is picked
// arbitrarily.
//
// == plan

/** Usage

> ./mill plan foo.compileClasspath
foo.transitiveCompileClasspath
foo.compileResources
foo.unmanagedClasspath
foo.scalaVersion
foo.platformSuffix
foo.compileIvyDeps
foo.scalaOrganization
foo.scalaLibraryIvyDeps
foo.ivyDeps
foo.transitiveIvyDeps
foo.compileClasspath

*/

// `mill plan foo` shows which tasks would be evaluated if you ran `mill foo`,
// and in what order, but without actually running them. This is a useful tool for
// debugging your build: e.g. if you suspect a task `foo` is running things that
// it shouldn't be running, a quick `mill plan` will list out all the upstream
// tasks that `foo` needs to run, and you can then follow up with `mill path` on
// any individual upstream task to see exactly how `foo` depends on it.
//
// == clean

/** Usage

> ./mill clean

*/

// `clean` deletes all the cached outputs of previously executed tasks. It can
// apply to the entire project, entire modules, or specific tasks.

/** Usage

> ./mill clean             # clean all outputs
> ./mill clean foo         # clean all outputs for module 'foo' (including nested modules)
> ./mill clean foo.compile # only clean outputs for task 'compile' in module 'foo'
> ./mill clean foo.{compile,run}
> ./mill clean "foo.{compile,run}"
> ./mill clean foo.compile foo.run
> ./mill clean _.compile
> ./mill clean __.compile

*/

// == Search for dependency updates

/** Usage

> ./mill mill.scalalib.Dependency/showUpdates

> ./mill mill.scalalib.Dependency/showUpdates --allowPreRelease true # also show pre-release versions

*/

// Mill can search for updated versions of your project's dependencies, if
// available from your project's configured repositories. Note that it uses
// heuristics based on common versioning schemes, so it may not work as expected for
// dependencies with particularly weird version numbers.
//
// Current limitations:
//
// * Only works for `JavaModule` modules (including ``ScalaModule``s,
// ``CrossScalaModule``s, etc.) and Maven repositories.
// * Always applies to all modules in the build.
// * Doesn't apply to `$ivy` dependencies used in the build definition itself.
//
