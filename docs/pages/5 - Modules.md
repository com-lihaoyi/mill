Mill modules are `object`s extending `mill.Module`, and let you group related
tasks together to keep things neat and organized. Mill's comes with built in
modules such as `mill.scalalib.ScalaModule` and `mill.scalalib.CrossSbtModule`,
but you can use modules for other purposes as well.

## Using Modules

The path to a Mill module from the root of your build file corresponds to the
path you would use to run tasks within that module from the command line. e.g.
for the following build:

```scala
object foo extends mill.Module {
  def bar = T { "hello" }
  object baz extends mill.Module {
    def qux = T { "world" } 
  } 
}
```

You would be able to run the two targets via `mill foo.bar` or `mill
foo.baz.qux`. You can use `mill show foo.bar` or `mill show foo.baz.qux` to
make Mill echo out the string value being returned by each Target. The two
targets will store their output metadata & files at `./out/foo/bar` and
`./out/foo/baz/qux` respectively.

Modules also provide a way to define and re-use common collections of tasks, via
Scala `trait`s. For example, you can define your own `FooModule` trait:

```scala
trait FooModule extends mill.Module {
  def bar = T { "hello" }
  def baz = T { "world" }
}
```

And use it to define multiple modules with the same `bar` and `baz` targets,
along with any other customizations such as `qux`:

```scala
object foo1 extends FooModule
object foo2 extends FooModule {
  def qux = T { "I am Cow" }
}  
```

This would make the following targets available from the command line

- `mill show foo1.bar`
- `mill show foo1.baz`
- `mill show foo2.bar`
- `mill show foo2.baz`
- `mill show foo2.qux`

The built-in `mill.scalalib` package uses this to define
`mill.scalalib.ScalaModule`, `mill.scalalib.SbtModule` and
`mill.scalalib.TestScalaModule`, all of which contain a set of "standard"
operations such as `compile` `jar` or `assembly` that you may expect from a
typical Scala module.

When defining your own module abstractions, in general you should use `trait`s
and not `class`es, except in the case of
[Cross Builds](http://www.lihaoyi.com/mill/page/cross-builds.html).

## Overriding Targets

```scala
trait BaseModule extends Module {
  def foo = T { Seq("base") }
  def cmd(i: Int) = T.command { Seq("base" + i) }
}

object canOverrideSuper with BaseModule {
  def foo = T { super.foo() ++ Seq("object") }
  def cmd(i: Int) = T.command { super.cmd(i)() ++ Seq("object" + i) }
}
```

You can override targets and commands to customize them or change what they do.
The overriden version is available via `super`. You can omit the `override`
keyword in Mill builds.

## millSourcePath

Each Module has a `millSourcePath` field that corresponds to the path that module
expects its input files to be on disk. Re-visiting our examples above:

```scala
object foo extends mill.Module {
  def bar = T { "hello" }
  object baz extends mill.Module {
    def qux = T { "world" } 
  } 
}
```

The `foo` module has a `millSourcePath` of `./foo`, while the `foo.baz` module has a
`millSourcePath` of `./foo/baz`.

You can use `millSourcePath` to automatically set the source folders of your
modules to match the build structure. You are not forced to rigidly use
`millSourcePath` to define the source folders of all your code, but it can simplify
the common case where you probably want your build-layout and on-disk-layout to
be the same.

E.g. for `mill.scalalib.ScalaModule`, the Scala source code is assumed by
default to be in `millSourcePath/"src"` while resources are automatically assumed to
be in `millSourcePath/"resources"`.

You can override `millSourcePath`:

```scala
object foo extends mill.Module {
  def millSourcePath = super.millSourcePath / "lols"
  def bar = T { "hello" }
  object baz extends mill.Module {
    def qux = T { "world" } 
  } 
}
```

And any overrides propagate down to the module's children: in the above example,
module `foo` would have its `millSourcePath` be `./foo/lols` while module` foo.baz`
would have its `millSourcePath` be `./foo/lols/baz`.

Note that `millSourcePath` is generally only used for a module's input source files.
Output is always in the `out/` folder and cannot be changed, e.g. even with the
overriden `millSourcePath` the output paths are still the default `./out/foo/bar` and
`./out/foo/baz/qux` folders.

## External Modules

Libraries for use in Mill can define `ExternalModule`s: `Module`s which are
shared between all builds which use that library:

```scala
package foo
import mill._

object Bar extends mill.define.ExternalModule {
  def baz = T { 1 }
  def qux() = T.command { println(baz() + 1) }

  lazy val millDiscover = mill.define.Discover[this.type]
}
```

In the above example, `foo.Bar` is an `ExternalModule` living within the `foo`
Java package, containing the `baz` target and `qux` command. Those can be run
from the command line via:

```bash
mill foo.Bar/baz
mill foo.Bar/qux
```

`ExternalModule`s are useful for someone providing a library for use with Mill
that is shared by the entire build: for example,
`mill.scalalib.ZincWorkerApi/zincWorker` provides a shared Scala compilation
service & cache that is shared between all `ScalaModule`s, and
`mill.scalalib.GenIdea/idea` lets you generate IntelliJ projects without
needing to define your own `T.command` in your `build.sc` file

## Foreign Modules 

Mill can load other mill projects from external (or sub) folders, 
using Ammonite's `$file` magic import, allowing to depend on foreign modules. 
This allows, for instance, to depend on other projects' sources, or split 
your build logic into smaller files.
     

For instance, assuming the following stucture : 

```text
foo/
    build.sc
    bar/
        build.sc 
baz/
    build.sc     
```

you can write the following in `foo/build.sc` : 

```scala

import $file.bar.build
import $file.^.baz.build
import mill._ 

def someFoo = T {

    ^.baz.build.someBaz(...)
    bar.build.someBar(...) 
    ...
}
```

The output of the foreign tasks will be cached under `foo/out/foreign-modules/`. 
