// == Using Cross Modules from other Cross Modules
//
// Targets in cross-modules can depend on one another the same way than
// external targets:
import mill._

object foo extends mill.Cross[FooModule]("2.10", "2.11", "2.12")
trait FooModule extends Cross.Module[String] {
  def suffix = T { "_" + crossValue }
}

object bar extends mill.Cross[BarModule]("2.10", "2.11", "2.12")
trait BarModule extends Cross.Module[String] {
  def bigSuffix = T { "[[[" + foo(crossValue).suffix() + "]]]" }
}

/** Usage

> ./mill showNamed foo[__].suffix
{
  "foo[2.10].suffix": "_2.10",
  "foo[2.11].suffix": "_2.11",
  "foo[2.12].suffix": "_2.12"
}

> ./mill showNamed bar[__].bigSuffix
{
  "bar[2.10].bigSuffix": "[[[_2.10]]]",
  "bar[2.11].bigSuffix": "[[[_2.11]]]",
  "bar[2.12].bigSuffix": "[[[_2.12]]]"
}

*/