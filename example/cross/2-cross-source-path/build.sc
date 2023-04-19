import mill._

object foo extends Cross[FooModule]("2.10", "2.11", "2.12")
trait FooModule extends Cross.Module[String] {
  def millSourcePath = super.millSourcePath / crossValue
  def sources = T.sources(millSourcePath)
}

// This example shows off the core logic of `Cross` modules

/* Example Usage

> mill show foo[2.10].sources
foo/2.10

> mill show foo[2.11].sources
foo/2.11

> mill show foo[2.12].sources
foo/2.12

*/