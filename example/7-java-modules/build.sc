import mill._, scalalib._

object foo extends JavaModule{
  def moduleDeps = Seq(bar)
}

object bar extends JavaModule

/* Example Usage

> ./mill resolve __.run
foo.run
bar.run

> ./mill foo.run
Foo.value: 31337
Bar.value: 271828

*/