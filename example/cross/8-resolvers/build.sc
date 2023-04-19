import mill._

import mill._

trait MyModule extends Cross.Module[String] {
  def crossVersion: String
  implicit object resolver extends mill.define.Cross.Resolver[MyModule] {
    def resolve[V <: MyModule](c: Cross[V]): V = c.itemMap(List(crossVersion))
  }
}

object foo extends mill.Cross[FooModule]("2.10", "2.11", "2.12")
trait FooModule extends MyModule {
  def suffix = T { crossValue }
}

object bar extends mill.Cross[BarModule]("2.10", "2.11", "2.12")
trait BarModule extends MyModule {
  def longSuffix = T { "_" + foo().suffix() }
}

/* Example Usage

*/