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

## Adding Ivy Dependencies

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

## Adding a Test Suite

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


## Scala Compiler Plugins

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

## Common Configuration

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

## Custom Tasks

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

You can print the value of your custom target using `show`, e.g.

```bash
mill run show lineCount
```

You can define new un-cached Commands using the `T.command{...}` syntax. These
are un-cached and re-evaluate every time you run them, but can take parameters.
Their return type needs to be JSON-writable as well, or `(): Unit` if you want
to return nothing.

## Custom Modules

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

## Overriding Tasks

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
    println("Running..." + args.mkString(" "))
    super.run(args:_*)
  }
}
```

You can re-define targets and commands to override them, and use `super` if you
want to refer to the originally defined task. The above example shows how to
override `compile` and `run` to add additional logging messages.

In Mill builds the `override` keyword is optional.
