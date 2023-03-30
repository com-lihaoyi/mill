import mill._, scalalib._

object foo extends JavaModule{
  def moduleDeps = Seq(bar)
}

object bar extends JavaModule

// Mill also supports `JavaModule`s, which can only contain pure Java code
// without any Scala. These have the same set of tasks as `ScalaModules`:
// `compile`, `run`, etc., and can similarly depend on each other.

/* Example Usage

> ./mill resolve __.run
foo.run
bar.run

> ./mill foo.compile
compiling 1 Java source

> ./mill foo.run
Foo.value: 31337
Bar.value: 271828

*/