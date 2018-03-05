You can configure your Mill build in a number of ways:

## Compilation & Execution Flags

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

If you want to pass main-method arguments to `run` or `runLocal`, simply pass
them after the `foo.run`/`foo.runLocal`:

```bash
mill foo.run arg1 arg2 arg3
mill foo.runLocal arg1 arg2 arg3
```

## Adding Ivy Dependencies

```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::upickle:0.5.1",
    ivy"com.lihaoyi::pprint:0.5.2",
    ivy"com.lihaoyi::fansi:0.2.4",
    ivy"org.scala-lang:scala-reflect:${scalaVersion()}"
  )
}
```

You can define the `ivyDeps` field to add ivy dependencies to your module. The
`ivy"com.lihaoyi::upickle:0.5.1"` syntax (with `::`) represents Scala
dependencies; for Java dependencies you would use a single `:` e.g.
`ivy"com.lihaoyi:upickle:0.5.1"`. If you have dependencies cross-published
against the full Scala version (eg. `2.12.4` instead of just `2.12`),
you can use `:::` as in `ivy"org.scalamacros:::paradise:2.1.1"`.

By default these are resolved from maven central, but you can add your own
resolvers by overriding the `repositories` definition in the module:

```scala
import coursier.maven.MavenRepository

def repositories = super.repositories ++ Seq(
  MavenRepository("https://oss.sonatype.org/content/repositories/releases")
)
```

## Adding a Test Suite

```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"

  object test extends Tests{ 
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
    def testFrameworks = Seq("utest.runner.Framework")
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

If you want to pass any arguments to the test framework, simply put them after
`foo.test` in the command line. e.g. [uTest](https://github.com/lihaoyi/utest)
lets you pass in a selector to decide which test to run, which in Mill would be:

```bash
mill foo.test foo.MyTestSuite.testCaseName
```

You can define multiple test suites if you want, e.g.:

```scala
// build.sc
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"

  object test extends Tests{ 
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
    def testFrameworks = Seq("utest.runner.Framework")
  }
  object integration extends Tests{ 
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
    def testFrameworks = Seq("utest.runner.Framework")
  }
}
```

Each of which will expect their sources to be in their respective `foo/test` and
`foo/integration` folder.

`Tests` modules are `ScalaModule`s like any other, and all the same
configuration options apply.

## Custom Test Frameworks

```scala
// build.sc
import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
  def ivyDeps = Agg(ivy"org.scalatest::scalatest:3.0.4")
  def testFrameworks = Seq("org.scalatest.tools.Framework")
}
```

Integrating with test frameworks like Scalatest is simply a matter of adding it
to `ivyDeps` and specifying the `testFrameworks` you want to use. After that you
can [add a test suite](#adding-a-test-suite) and `mill foo.test` as usual,
passing args to the test suite via `mill foo.test arg1 arg2 arg3`

## Scala Compiler Plugins

```scala
// build.sc
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

## Common Configuration

```scala
// build.sc
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

## Global configuration

Mill builds on ammonite which allows you to 
[define global configuration](http://ammonite.io/#ScriptPredef). Depending on 
how you start mill 2 different files will be loaded. For interactive mode it's 
`~/.mill/ammonite/predef.sc` and from the command line it's 
`~/.mill/ammonite/predefScript.sc`. You might want to create a symlink from one 
to the other to avoid duplication.

Example `~/.mill/ammonite/predef.sc`
```scala
val nexusUser = "myuser"
val nexusPassword = "mysecret"
```

Everything declared in the above file will be available to any build you run.

```scala
  def repositories = super.repositories ++ Seq(
    // login and pass are globally configured
    MavenRepository("https://nexus.mycompany.com/repository/maven-releases", authentication = Some(coursier.core.Authentication(nexusUser, nexusPassword)))
  )
```

## Custom Tasks

```scala
// build.sc
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
}

def lineCount = T{
  import ammonite.ops._
  foo.sources().flatMap(ref => ls.rec(ref.path)).filter(_.isFile).flatMap(read.lines).size
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

You can print the value of your custom target using `show`, e.g.

```bash
mill run show lineCount
```

You can define new un-cached Commands using the `T.command{...}` syntax. These
are un-cached and re-evaluate every time you run them, but can take parameters.
Their return type needs to be JSON-writable as well, or `(): Unit` if you want
to return nothing.

Your custom targets can depend on each other using the `def bar = T{... foo()
...}` syntax, and you can create arbitrarily long chains of dependent targets.
Mill will handle the re-evaluation and caching of the targets' output for you,
and will provide you a `T.ctx().dest` folder for you to use as scratch space or
to store files you want to return.

Custom targets and commands can contain arbitrary code. Whether you want to
download files (e.g. using `mill.modules.Util.download`), shell-out to Webpack
to compile some Javascript, generate sources to feed into a compiler, or create
some custom jar/zip assembly with the files you want (e.g. using
`mill.modules.Jvm.createJar`), all of these can simply be custom targets with
your code running in the `T{...}` block.

## Custom Modules

```scala
// build.sc
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
// build.sc
trait MySpecialModule extends Module{
  ...
}
object foo extends MySpecialModule
object bar extends MySpecialModule
```

## Overriding Tasks

```scala
// build.sc
import mill._
import mill.scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
  def compile = T{
    println("Compiling...")
    super.compile()
  }
  def run(args: String*) = T.command{
    println("Running..." + args.mkString(" "))
    super.run(args:_*)
  }
}
```

You can re-define targets and commands to override them, and use `super` if you
want to refer to the originally defined task. The above example shows how to
override `compile` and `run` to add additional logging messages, but you can
also override `ScalaModule#generatedSources` to feed generated code to your
compiler, `ScalaModule#prependShellScript` to make your assemblies executable,
or `ScalaModule#console` to use the Ammonite REPL instead of the normal Scala
REPL.

In Mill builds the `override` keyword is optional.

## Unmanaged Jars

```scala
// build.sc
import mill._
import mill.scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
  def unmanagedClasspath = T{
    if (!ammonite.ops.exists(millSourcePath / "lib")) Agg()
    else Agg.from(ammonite.ops.ls(millSourcePath / "lib"))
  }
}
```

You can override `unmanagedClasspath` to point it at any jars you place on the
filesystem, e.g. in the above snippet any jars that happen to live in the
`foo/lib/` folder.

## Defining a Main Class

```scala
// build.sc
import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
  def mainClass = Some("foo.bar.Baz")
}
```

Mill's `foo.run` by default will discover which main class to run from your
compilation output, but if there is more than one or the main class comes from
some library you cna explicitly specify which one to use. This also adds the
main class to your `foo.jar` and `foo.assembly` jars.

## Downloading Non-Maven Jars

```scala
// build.sc
import mill._
import mill.scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
  def unmanagedClasspath = Agg(
    mill.modules.Util.download(
      "https://github.com/williamfiset/FastJavaIO/releases/download/v1.0/fastjavaio.jar",
      "fastjavaio.jar"
    )
  )
}
```

You can also override `unmanagedClasspath` to point it at jars that you want to
download from arbitrary URLs. Note that targets like `unmanagedClasspath` are
cached, so your jar is downloaded only once and re-used indefinitely after that.
