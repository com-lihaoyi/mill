import mill._

object foo extends Cross[FooModule]("2.10", "2.11", "2.12")

trait FooModule extends Cross.Module[String] {
  def suffix = T { "_" + crossValue }
}

object bar extends Cross[FooModule]("2.10", "2.11", "2.12") {
  def defaultCrossSegments = Seq("2.12")
}


// For convenience, you can omit the selector for the default cross segment.
// By default, this is the first cross value specified.

/** Usage

> ./mill show foo[2.10].suffix
"_2.10"

> ./mill show foo[].suffix
"_2.10"

> ./mill show bar[].suffix
"_2.12"

*/


