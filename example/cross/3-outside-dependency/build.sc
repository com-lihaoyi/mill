// You can refer to targets defined in cross-modules as follows:

import mill._

object foo extends Cross[FooModule]("2.10", "2.11", "2.12")
trait FooModule extends Cross.Module[String] {
  def suffix = T { "_" + crossValue }
}

def bar = T { s"hello ${foo("2.10").suffix()}" }

def qux = T { s"hello ${foo("2.10").suffix()} world ${foo("2.12").suffix()}" }

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
//   "foo[2.12].suffix" -> "qux" [constraint=false]
//   "foo[2.11].suffix" -> "qux" [style=invis]
//   "foo[2.10].suffix" -> "qux"
//   "foo[2.10].suffix" -> "bar"
// }
// ....


// Here, `def bar` uses `foo("2.10")` to reference the `"2.10"` instance of
// `FooModule`. You can refer to whatever versions of the cross-module you want,
// even using multiple versions of the cross-module in the same target as we do
// in `def qux`.

/** Usage

> mill show foo[2.10].suffix
"_2.10"

> mill show bar
"hello _2.10"

> mill show qux
"hello _2.10 world _2.12"

*/