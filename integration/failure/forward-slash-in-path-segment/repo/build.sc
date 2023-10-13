import mill._

object foo extends Cross[Foo]("a/b")
trait Foo extends Cross.Module[String]
