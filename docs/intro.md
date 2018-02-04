Mill is a general purpose build-tool. It has built in support for the
[Scala](https://www.scala-lang.org/) programming language, and can serve as a
replacement for [SBT](http://www.scala-sbt.org/), but can also be extended to
support any other language or platform via modules (written in Java or Scala) or
through external subprocesses. Mill aims for simplicity by re-using concepts you
are already familiar with to let you define your project's build. Mill's
`build.sc` files are Scala scripts.


## Hello Mill

The simplest Mill build for a Scala project looks as follows:

```scala
import mill._
import mill.scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
}
```

This would build a project laid out as follows:

```
build.sc
foo/
    src/
        Main.scala
    resources/
        ...
out/
    foo/
        ... 
```

The source code for this module would live in the `foo/src/` folder, matching
the name you assigned to the module. Output for this module (compiled files,
resolved dependency lists, ...) would live in `out/foo/`.

This can be run from the Bash shell via:

```bash
$ mill foo.compile        # compile sources into classfiles

$ mill foo.run            # run the main method, if any

$ mill foo.jar            # bundle the classfiles into a jar

$ mill foo.assembly       # bundle the classfiles and all dependencies into a jar 
```

The most common **tasks** that Mill can run are cached **targets**, such as
`compile`, and un-cached **commands** such as `foo.run`. Targets do not
re-evaluate unless one of their inputs changes, where-as commands re-run every
time.

### Watch and Re-evaluate

You can use the `--watch` flag to make Mill watch a task's inputs, re-evaluating
the task as necessary when the inputs change:

```bash
$ mill --watch foo.compile 
$ mill --watch foo.run 
```

### Show Target Output

By default, Mill does not print out the metadata from evaluating a task. Most
people would not be interested in e.g. viewing the metadata related to
incremental compilation: they just want to compile their code! However, if you
want to inspect the build to debug problems, you can make Mill show you the
metadata output for a task using the `--show` flag:

You can also ask Mill to display the metadata output of a task using `--show`:

```bash
$ mill --show foo.compile
{
    "analysisFile": "/Users/lihaoyi/Dropbox/Github/test/out/foo/compile/dest/zinc",
    "classes": {
        "path": "/Users/lihaoyi/Dropbox/Github/test/out/foo/compile/dest/classes"
    }
}
```

This also applies to tasks which hold simple configurable values:

```bash
$ mill --show foo.sources
[
    {"path": "/Users/lihaoyi/Dropbox/Github/test/foo/src"}
]

$ mill --show foo.compileDepClasspath
[
    {"path": ".../org/scala-lang/scala-compiler/2.12.4/scala-compiler-2.12.4.jar"},
    {"path": ".../org/scala-lang/scala-library/2.12.4/scala-library-2.12.4.jar"},
    {"path": ".../org/scala-lang/scala-reflect/2.12.4/scala-reflect-2.12.4.jar"},
    {"path": ".../org/scala-lang/modules/scala-xml_2.12/1.0.6/scala-xml_2.12-1.0.6.jar"}
]
```

Any flags passed *before* the name of the task (e.g. `foo.compile`) are given to
Mill, while any arguments passed *after* the task are given to the task itself.
For example:

```bash
$ mill --watch foo.run
```

Makes Mill watch-and-re-evaluate the `foo.run` task, while `mill foo.run
--watch` evaluates `foo.run` once and passes it the `--watch` flag. This matches
the behavior of other executables such as `java` or `python`.

### The Build Repl

```bash
$ mill
Loading...
@ foo
res1: foo.type = ammonite.predef.build#foo:2
Commands:
    .runLocal(args: String*)()
    .run(args: String*)()
    .runMainLocal(mainClass: String, args: String*)()
    .runMain(mainClass: String, args: String*)()
    .console()()
Targets:
    .allSources()
    .artifactId()
    .artifactName()
...

@ foo.compile
res3: mill.package.T[mill.scalalib.CompilationResult] = mill.scalalib.ScalaModule#compile:152
Inputs:
    foo.scalaVersion
    foo.allSources
    foo.compileDepClasspath
...
    
@ foo.compile()
res2: mill.scalalib.CompilationResult = CompilationResult(
  root/'Users/'lihaoyi/'Dropbox/'Github/'test/'out/'foo/'compile/'dest/'zinc,
  PathRef(root/'Users/'lihaoyi/'Dropbox/'Github/'test/'out/'foo/'compile/'dest/'classes, false)
)
```

You can run `mill` alone to open a build REPL; this is a Scala console with your
`build.sc` loaded, which lets you run tasks interactively. The task-running
syntax is slightly different from the command-line, but more in-line with how
you would depend on tasks from within your build file.

You can use this REPL to run build commands quicker, due to keeping the JVM warm
between runs, or to interactively explore your build to see what is available.


## Configuring Mill

You can configure your Mill build in a number of ways:

### Compilation & Execution Flags

```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
  
  def scalacOptions = Seq("-Ydelambdafy:inline")
  
  def forkArgs = Seq("-Xmx4g")
  
  def forkEnv = Map("HELLO_MY_ENV_VAR" -> "WORLD")
}
```

You can pass flags to the Scala compiler via `scalacOptions`. By default,
`foo.run` runs the compiled code in a subprocess, and you can pass in JVM flags
via `forkArgs` or environment-variables via `forkEnv`.

You can also run your code via

```bash
mill foo.runLocal
```

Which runs it in-process within an isolated classloader. This may be faster
since you avoid the JVM startup, but does not support `forkArgs` or `forkEnv`.

### Adding Ivy Dependencies

```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::upickle:0.5.1",
    ivy"com.lihaoyi::pprint:0.5.2",
    ivy"com.lihaoyi::fansi:0.2.4"
  )
}
```

You can define the `ivyDeps` field to add ivy dependencies to your module. The
`ivy"com.lihaoyi::upickle:0.5.1"` syntax (with `::`) represents Scala
dependencies; for Java dependencies you would use a single `:` e.g.
`ivy"com.lihaoyi:upickle:0.5.1"`.

By default these are resolved from maven central, but you can add your own
resolvers by overriding the `repositories` definition in the module:

```scala
def repositories = super.repositories ++ Seq(
  MavenRepository("https://oss.sonatype.org/content/repositories/releases")
)
```

### Adding a Test Suite

```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"

  object test extends Tests{ 
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
    def testFramework = "mill.UTestFramework"
  }
}
```

You can define a test suite by creating a nested module extending `Tests`, and
specifying the ivy coordinates and name of your test framework. This expects the
tests to be laid out as follows:

```
build.sc
foo/
    src/
        Main.scala
    resources/
        ...
    test/
        src/
            MainTest.scala
        resources/
            ...
out/
    foo/
        ...
        test/
            ...
```

The above example can be run via

```bash
mill foo.test
```

By default, tests are run in a subprocess, and `forkArg` and `forkEnv` can be
overriden to pass JVM flags & environment variables. You can also use

```bash
mill foo.test.testLocal
```

To run tests in-process in an isolated classloader.

You can define multiple test suites if you want, e.g.:

```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"

  object test extends Tests{ 
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
    def testFramework = "mill.UTestFramework"
  }
  object integration extends Tests{ 
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
    def testFramework = "mill.UTestFramework"
  }
}
```

Each of which will expect their sources to be in their respective `foo/test` and
`foo/integration` folder.

`Tests` modules are `ScalaModule`s like any other, and all the same
configuration options apply.

### Multiple Modules

```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
}
object bar extends ScalaModule {
  def moduleDeps = Seq(foo)
  def scalaVersion = "2.12.4"
}
```

You can define multiple modules the same way you define a single module, using
`def moduleDeps` to define the relationship between them. The above build
expects the following project layout:

```
build.sc
foo/
    src/
        Main.scala
    resources/
        ...
bar/
    src/
        Main2.scala
    resources/
        ...
out/
    foo/
        ... 
    bar/
        ... 
```

Mill's evaluator will ensure that the modules are compiled in the right order,
and re-compiled as necessary when source code in each module changes.

Modules can also be nested:

```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
  object bar extends ScalaModule {
    def moduleDeps = Seq(foo)
    def scalaVersion = "2.12.4"
  }
}
```

Which would result in a similarly nested project layout:

```
build.sc
foo/
    src/
        Main.scala
    resources/
        ...
    bar/
        src/
            Main2.scala
        resources/
            ...
out/
    foo/
        ...
        bar/
            ...
```

### Scala Compiler Plugins

```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
  
  def compileIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")
  def scalacOptions = Seq("-P:acyclic:force")
  def scalacPluginIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")
}
```

You can use Scala compiler plugins by setting `scalacPluginIvyDeps`. The above
example also adds the plugin to `compileIvyDeps`, since that plugin's artifact
is needed on the compilation classpath (though not at runtime).

### Common Configuration

```scala
import mill._
import mill.scalalib._
trait CommonModule extends ScalaModule{
  def scalaVersion = "2.12.4"
}
 
object foo extends CommonModule
object bar extends CommonModule {
  def moduleDeps = Seq(foo)
}
```

You can extract out configuration common to multiple modules into a `trait` that
those modules extend. This is useful for providing convenience & ensuring
consistent configuration: every module often has the same scala-version, uses
the same testing framework, etc. and all that can be extracted out into the
`trait`.

### Custom Tasks

```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
}

def lineCount = T{
  import ammonite.ops._
  foo.sources().flatMap(ref => ls.rec(ref.path)).flatMap(read.lines).size
}

def printLineCount() = T.command{
  println(lineCount())
}
```

You can define new cached Targets using the `T{...}` syntax, depending on
existing Targets e.g. `foo.sources` via the `foo.sources()` syntax to extract
their current value, as shown in `lineCount` above. The return-type of a Target
has to be JSON-serializable (using
[uPickle](https://github.com/lihaoyi/upickle)) and the Target is cached when
first run until it's inputs change (in this case, if someone edits the
`foo.sources` files which live in `foo/src`. Cached Targets cannot take
parameters.

You can print the value of your custom target using `--show`, e.g.

```bash
mill run --show lineCount
```

You can define new un-cached Commands using the `T.command{...}` syntax. These
are un-cached and re-evaluate every time you run them, but can take parameters.
Their return type needs to be JSON-writable as well, or `(): Unit` if you want
to return nothing.

### Custom Modules

```scala
import mill._
import mill.scalalib._
object qux extends Module{
  object foo extends ScalaModule {
    def scalaVersion = "2.12.4"
  }
  object bar extends ScalaModule {
    def moduleDeps = Seq(foo)
    def scalaVersion = "2.12.4"
  }
}
```

Not every Module needs to be a `ScalaModule`; sometimes you just want to group
things together for neatness. In the above example, you can run `foo` and `bar`
namespaced inside `qux`:

```bash
mill qux.foo.compile
mill qux.bar.run
```

You can also define your own module traits, with their own set of custom tasks,
to represent other things e.g. Javascript bundles, docker image building,:

```scala
trait MySpecialModule extends Module{
  ...
}
object foo extends MySpecialModule
object bar extends MySpecialModule
```

### Overriding Tasks

```scala
import mill._
import mill.scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
  def compile = T{
    println("Compiling...")
    super.compile()
  }
  def run(args: String*) = T.command{
    println("Running... + args.mkString(" "))
    super.run(args:_*)
  }
}
```

You can re-define targets and commands to override them, and use `super` if you
want to refer to the originally defined task. The above example shows how to
override `compile` and `run` to add additional logging messages.

In Mill builds the `override` keyword is optional.

### Publishing Modules

## Common Project Layouts

Above, we have shown how to work with the Mill default Scala module layout. Here
we will explore some other common project layouts that you may want in your
Scala build:

### Cross Scala-Version Modules

```scala
import mill._
import mill.scalalib._
object foo extends Cross[FooModule]("2.10.6", "2.11.11", "2.12.4")
class FooModule(val crossScalaVersion: String) extends CrossScalaModule{
   ...
}
```

Mill provides a `CrossScalaModule` template, which can be used with `Cross` to
cross-build Scala modules across different versions of Scala. The default
configuration for `CrossScalaModule` expects a filesystem layout as follows:

```text
build.sc
foo/
    src/
    src-2.10/
    src-2.11/
    src-2.12/
    test/
        src/
        src-2.10/
        src-2.11/
        src-2.12/
```

Code common to all Scala versions lives in `src`, while code specific to one
version lives in `src-x.y`.

### Scala.js Modules

```scala
import mill._
import mill.scalajslib._

object foo extends ScalaJSModule {
  def scalaVersion = "2.12.4"
  def scalaJSVersion = "0.6.22"
}
```

`ScalaJSModule` is a variant of `ScalaModule` that builds your code using
Scala.js. In addition to the standard `foo.compile` and `foo.run` commands (the
latter of which runs your code on Node.js, which must be pre-installed)
`ScalaJSModule` also exposes the `foo.fastOpt` and `foo.fullOpt` tasks for
generating the optimized Javascript file.

### Cross Scala-JVM/Scala.js Modules

### Cross Scala-Version Scala-JVM/JS Modules

### SBT-Compatible Modules

```scala
import mill._
import mill.scalalib._

object foo extends SbtModule {
  def scalaVersion = "2.12.4"
}
```

These are basically the same as normal `ScalaModule`s, but configured to follow
the SBT project layout:

```text
build.sc
foo/
    src/
        main/
            scala/
        test/
            scala/
```

Useful if you want to migrate an existing project built with SBT without having
to re-organize all your files


### SBT-Compatible Cross Scala-Version Modules

```scala
import mill._
import mill.scalalib._
object foo extends Cross[FooModule]("2.10.6", "2.11.11", "2.12.4")
class FooModule(val crossScalaVersion: String) extends CrossSbtModule{
   ...
}
```

A `CrossSbtModule` is a version of `CrossScalaModule` configured with the SBT
project layout:

```text
build.sc
foo/
    src/
        main/
            scala/
            scala-2.10/
            scala-2.11/
            scala-2.12/
        test/
            scala/
            scala-2.10/
            scala-2.11/
            scala-2.12/
```

### SBT-Compatible Cross Scala-Version Scala-JVM/JS Modules



## Example Builds

Mill comes bundled with example builds for existing open-source projects, as
integration tests and examples:


### Acyclic

- [Mill Build](https://github.com/lihaoyi/mill/blob/master/integration/test/resources/acyclic/build.sc#L1)

A small single-module cross-build, with few sources minimal dependencies


### Better-Files

- [Mill Build](https://github.com/lihaoyi/mill/blob/master/integration/test/resources/better-files/build.sc#L1)

A collection of small modules compiled for a single Scala version.

Also demonstrates how to define shared configuration in a `trait`, enable Scala
compiler flags, and download artifacts as part of the build.

### Jawn

- [Mill Build](https://github.com/lihaoyi/mill/blob/master/integration/test/resources/jawn/build.sc#L1)

A collection of relatively small modules, all cross-built across the same few
versions of Scala.


### Ammonite

- [Mill Build](https://github.com/lihaoyi/mill/blob/master/integration/test/resources/ammonite/build.sc#L1)

A relatively complex build with numerous submodules, some cross-built across
Scala major versions while others are cross-built against Scala minor versions.

Also demonstrates how to pass one module's compiled artifacts to the
`run`/`test` commands of another, via their `forkEnv`.

## Extending Mill