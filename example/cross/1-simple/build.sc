import mill._

object foo extends Cross[FooModule]("2.10", "2.11", "2.12")
trait FooModule extends Cross.Module[String] {
  def suffix = T { crossValue }
  def bigSuffix = T { "[[[" + suffix() + "]]]" }
  def sources = T.sources(millSourcePath)
}

// This defines three copies of `FooModule`: `"2.10"`, `"2.11"` and `"2.12"`,
// each of which has their own `suffix` target. You can then run them via

/* Example Usage

> mill show foo[2.10].suffix
"2.10"

> mill show foo[2.10].bigSuffix
"[[[2.10]]]"

> mill show foo[2.10].sources
foo

> mill show foo[2.11].suffix
"2.11"

> mill show foo[2.11].bigSuffix
"[[[2.11]]]"

> mill show foo[2.11].sources
foo

> mill show foo[2.12].suffix
"2.12"

> mill show foo[2.12].bigSuffix
"[[[2.12]]]"

> mill show foo[2.12].sources
foo

*/