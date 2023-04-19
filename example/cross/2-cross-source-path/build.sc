import mill._

object foo extends Cross[FooModule]("2.10", "2.11", "2.12")
trait FooModule extends Cross.Module[String] {
  def millSourcePath = super.millSourcePath / crossValue
  def sources = T.sources(millSourcePath)
}

// By default, cross modules do not include the cross key as part of the
// `millSourcePath` for each module. This is the common case, where you are
// cross-building the same sources across different input versions. If you want
// to use a cross module to build different folders with the same config, you
// can do so by overriding `millSourcePath` as shown above.

/* Example Usage

> ./mill show foo[2.10].sources
foo/2.10

> ./mill show foo[2.11].sources
foo/2.11

> ./mill show foo[2.12].sources
foo/2.12

*/