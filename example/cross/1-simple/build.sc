// Mill handles cross-building of all sorts via the `Cross[T]` module.

import mill._

object foo extends Cross[FooModule]("2.10", "2.11", "2.12")
trait FooModule extends Cross.Module[String] {
  def suffix = T { "_" + crossValue }
  def bigSuffix = T { "[[[" + suffix() + "]]]" }
  def sources = T.sources(millSourcePath)
}

// Cross modules defined using the `Cross[T]` class allow you to define
// multiple copies of the same module, differing only in some input key. This
// is very useful for building the same module against different versions of a
// language or library, or creating modules to represent folders on the
// filesystem.
//
// This example defines three copies of `FooModule`: `"2.10"`, `"2.11"` and
// `"2.12"`, each of which has their own `suffix` target. You can then run
// them as shown below. Note that by default, `sources` returns `foo` for every
// cross module, assuming you want to build the same sources for each. This can
// be overriden

/** Usage

> ./mill show foo[2.10].suffix
"_2.10"

> ./mill show foo[2.10].bigSuffix
"[[[_2.10]]]"

> ./mill show foo[2.10].sources
[
  ".../foo"
]

> ./mill show foo[2.12].suffix
"_2.12"

> ./mill show foo[2.12].bigSuffix
"[[[_2.12]]]"

> ./mill show foo[2.12].sources
[
  ".../foo"
]

*/
