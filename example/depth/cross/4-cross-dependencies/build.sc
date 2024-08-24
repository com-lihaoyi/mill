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

// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   subgraph cluster_2 {
//     label="foo[2.12]"
//     style=dashed
//     "foo[2.12].suffix"
//   }
//   subgraph cluster_1 {
//     label="foo[2.11]"
//     style=dashed
//     "foo[2.11].suffix"
//   }
//   subgraph cluster_0 {
//     label="foo[2.10]"
//     style=dashed
//     "foo[2.10].suffix"
//   }
//   subgraph cluster_4 {
//     label="bar[2.11]"
//     style=dashed
//     "bar[2.11].bigSuffix"
//   }
//   subgraph cluster_5 {
//     label="bar[2.12]"
//     style=dashed
//     "bar[2.12].bigSuffix"
//   }
//   subgraph cluster_3 {
//     label="bar[2.10]"
//     style=dashed
//     "bar[2.10].bigSuffix"
//   }
//   "foo[2.10].suffix" -> "bar[2.10].bigSuffix"
//   "foo[2.11].suffix" -> "bar[2.11].bigSuffix"
//   "foo[2.12].suffix" -> "bar[2.12].bigSuffix"
// }
// ....

// Rather than pssing in a literal `"2.10"` to the `foo` cross module, we pass
// in the `crossValue` property that is available within every `Cross.Module`.
// This ensures that each version of `bar` depends on the corresponding version
// of `foo`: `bar("2.10")` depends on `foo("2.10")`, `bar("2.11")` depends on
// `foo("2.11")`, etc.

/** Usage

> mill showNamed foo[__].suffix
{
  "foo[2.10].suffix": "_2.10",
  "foo[2.11].suffix": "_2.11",
  "foo[2.12].suffix": "_2.12"
}

> mill showNamed bar[__].bigSuffix
{
  "bar[2.10].bigSuffix": "[[[_2.10]]]",
  "bar[2.11].bigSuffix": "[[[_2.11]]]",
  "bar[2.12].bigSuffix": "[[[_2.12]]]"
}

*/