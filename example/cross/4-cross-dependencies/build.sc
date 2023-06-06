// Targets in cross-modules can use one another the same way they are used from
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

// Rather than pssing in a literal `"2.10"` to the `foo` cross module, we pass
// in the `crossValue` property that is available within every `Cross.Module`.
// This ensures that each version of `bar` depends on the corresponding version
// of `foo`: `bar("2.10")` depends on `foo("2.10")`, `bar("2.11")` depends on
// `foo("2.11")`, etc.

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