Mill handles cross-building of all sorts via the `Cross[T]` module.


## Defining Cross Modules

You can use this as follows:

```scala
object foo extends mill.Cross[FooModule]("2.10", "2.11", "2.12")
class FooModule(crossVersion: String) extends Module {
  def suffix = T { crossVersion }
  def bigSuffix = T { suffix().toUpperCase() }
}
```

This defines three copies of `FooModule`: `"210"`, `"211"` and `"212"`, each of
which has their own `suffix` target. You can then run them via

```bash
mill show foo[2.10].suffix
mill show foo[2.10].bigSuffix
mill show foo[2.11].suffix
mill show foo[2.11].bigSuffix
mill show foo[2.12].suffix
mill show foo[2.12].bigSuffix
```

The modules each also have a `millSourcePath` of

```text
foo/2.10
foo/2.11
foo/2.12
```

And the `suffix` targets will have the corresponding output paths for their
metadata and files:

```text
foo/2.10/suffix
foo/2.10/bigSuffix
foo/2.11/suffix
foo/2.11/bigSuffix
foo/2.12/suffix
foo/2.12/bigSuffix
```

You can also have a cross-build with multiple inputs:

```scala
val crossMatrix = for {
  crossVersion <- Seq("210", "211", "212")
  platform <- Seq("jvm", "js", "native")
  if !(platform == "native" && crossVersion != "212")
} yield (crossVersion, platform)

object foo extends mill.Cross[FooModule](crossMatrix:_*)
class FooModule(crossVersion: String, platform: String) extends Module {
  def suffix = T { crossVersion + "_" + platform }
}
```

Here, we define our cross-values programmatically using a `for`-loop that spits
out tuples instead of individual values. Our `FooModule` template class then
takes two parameters instead of one. This creates the following modules each
with their own `suffix` target:

```bash
mill show foo[210,jvm].suffix
mill show foo[211,jvm].suffix
mill show foo[212,jvm].suffix
mill show foo[210,js].suffix
mill show foo[211,js].suffix
mill show foo[212,js].suffix
mill show foo[212,native].suffix
```

## Using Cross Modules from Outside

You can refer to targets defined in cross-modules as follows:

```scala
object foo extends mill.Cross[FooModule]("2.10", "2.11", "2.12")
class FooModule(crossVersion: String) extends Module {
  def suffix = T { crossVersion }
}

def bar = T { "hello " + foo("2.10").suffix } 
```

Here, `foo("2.10")` references the `"2.10"` instance of `FooModule`. You can
refer to whatever versions of the cross-module you want, even using multiple
versions of the cross-module in the same target:

```scala
object foo extends mill.Cross[FooModule]("2.10", "2.11", "2.12")
class FooModule(crossVersion: String) extends Module {
  def suffix = T { crossVersion }
}

def bar = T { "hello " + foo("2.10").suffix + " world " + foo("2.12").suffix }
```

## Using Cross Modules from other Cross Modules

Targets in cross-modules can depend on one another the same way that external
targets:

```scala
object foo extends mill.Cross[FooModule]("2.10", "2.11", "2.12")
class FooModule(crossVersion: String) extends Module {
  def suffix = T { crossVersion }
}

object bar extends mill.Cross[BarModule]("2.10", "2.11", "2.12")
class BarModule(crossVersion: String) extends Module {
  def bigSuffix = T { foo(crossVersion).suffix().toUpperCase() }
}
```

Here, you can run:

```bash
mill show foo[2.10].suffix
mill show foo[2.11].suffix
mill show foo[2.12].suffix
mill show bar[2.10].bigSuffix
mill show bar[2.11].bigSuffix
mill show bar[2.12].bigSuffix
```


## Cross Resolvers

You can define an implicit `mill.define.Cross.Resolver` within your
cross-modules, which would let you use a shorthand `foo()` syntax when referring
to other cross-modules with an identical set of cross values:

```scala
trait MyModule extends Module {
  def crossVersion: String
  implicit object resolver extends mill.define.Cross.Resolver[MyModule] {
    def resolve[V <: MyModule](c: Cross[V]): V = c.itemMap(List(crossVersion))
  }
}

object foo extends mill.Cross[FooModule]("2.10", "2.11", "2.12")
class FooModule(val crossVersion: String) extends MyModule {
  def suffix = T { crossVersion }
}

object bar extends mill.Cross[BarModule]("2.10", "2.11", "2.12")
class BarModule(val crossVersion: String) extends MyModule {
  def longSuffix = T { "_" + foo().suffix() }
}
```

While the example `resolver` simply looks up the target `Cross` value for the
cross-module instance with the same `crossVersion`, you can make the resolver
arbitrarily complex. E.g. the `resolver` for `mill.scalalib.CrossSbtModule`
looks for a cross-module instance whose `scalaVersion` is binary compatible
(e.g. 2.10.5 is compatible with 2.10.3) with the current cross-module.
