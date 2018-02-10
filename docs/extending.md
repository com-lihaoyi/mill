There are many different ways of extending Mill, depending on how much
customization and flexibility you need. This page will go through your options
from the easiest/least-flexible to the hardest/most-flexible.

## Custom Targets & Commands

The simplest way of adding custom functionality to Mill is to define a custom
Target or Command:

```scala
def foo = T{ ... }
def bar(x: Int, s: String) = T.command{ ... }
```

These can depend on other Targets, contain arbitrary code, and be placed
top-level or within any module. If you have something you just want to *do* that
isn't covered by the built-in `ScalaModule`s/`ScalaJSModule`s, simply write a
custom Target (for cached computations) or Command (for un-cached actions) and
you're done.

This covers use cases like:

### Compile some Javascript with Webpack and put it in your runtime classpath:

```scala
def doWebpackStuff(sources: Seq[PathRef]): PathRef = ???

def javascriptSources = T.sources{ millSourcePath / "js" }
def compiledJavascript = T{ doWebpackStuff(javascriptSources()) }  
object foo extends ScalaModule{
  def runClasspath = T{ super.runClasspath() ++ compiledJavascript() }
}
```

### Deploy your compiled assembly to AWS

```scala
object foo extends ScalaModule{

}

def deploy(assembly: PathRef, credentials: String) = ???

def deployFoo(credentials: String) = T.command{ deployFoo(foo.assembly()) }
```


## Custom Workers

[Custom Targets & Commands](#custom-targets--commands) are re-computed from
scratch each time; sometimes you want to keep values around in-memory when using
`--watch` or the Build REPL. e.g. you may want to keep a webpack process running
so webpack's own internal caches are hot and compilation is fast:

```scala
def webpackWorker = T.worker{
  // Spawn a process using java.lang.Process and return it
}

def javascriptSources = T.sources{ millSourcePath / "js" }

def doWebpackStuff(webpackProcess: Process, sources: Seq[PathRef]): PathRef = ???

def compiledJavascript = T{ doWebpackStuff(webpackWorker(), javascriptSources()) }
```

## Custom Modules

```scala
trait FooModule extends mill.Module{
  def bar = T{ "hello" }
  def baz = T{ "world" }
}
```

Custom modules are useful if you have a common set of tasks that you want to
re-used across different parts of your build. You simply define a `trait`
inheriting from `mill.Module`, and then use that `trait` as many times as you
want in various `object`s:

```scala
object foo1 extends FooModule
object foo2 extends FooModule{
  def qux = T{ "I am Cow" }
}  
```

You can also define a `trait` extending the built-in `ScalaModule` if you have
common configuration you want to apply to all your `ScalaModule`s:

```scala
trait FooModule extends ScalaModule{
  def scalaVersion = "2.11.11"
  object test extends Tests{
    def ivyDeps = Agg(ivy"org.scalatest::scalatest:3.0.4")
    def testFramework = "org.scalatest.tools.Framework"
  }
}
```

## import $file

If you want to define some functionality that can be used both inside and
outside the build, you can create a new `foo.sc` file next to your `build.sc`,
`import $file.foo`, and use it in your `build.sc` file:

```scala
// foo.sc
def fooValue() = 31337 
```
```scala
// build.sc
import $file.foo
def printFoo() = T.command{ println(foo.fooValue()) }
```

Mill's `import $file` syntax supports the full functionality of
[Ammonite Scripts](http://ammonite.io/#ScalaScripts)

## import $ivy

If you want to pull in artifacts from the public repositories (e.g. Maven
Central) for use in your build, you can simple use `import $ivy`:

```scala
// build.sc
import $ivy.`com.lihaoyi::scalatags:0.6.2`


def generatedHtml = T{
  import scalatags.Text.all._
  html(
    head(),
    body(
      h1("Hello"),
      p("World")
    )
  ).render  
}
```

This creates the `generatedHtml` target which can then be used however you would
like: written to a file, further processed, etc.

If you want to publish re-usable libraries that *other* people can use in their
builds, simply publish your code as a library to maven central.

For more information, see Ammonite's
[Ivy Dependencies documentation](http://ammonite.io/#import$ivy)

## Custom Main Methods

