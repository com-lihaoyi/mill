import mill._

object foo extends mill.Cross[FooModule]("2.10", "2.11", "2.12")
trait FooModule extends Cross.Module[String] {
  def suffix = T { crossVersion }
}

object bar extends mill.Cross[BarModule]("2.10", "2.11", "2.12")
trait BarModule extends Cross.Module[String] {
  def bigSuffix = T { "[[[" + foo(crossValue).suffix() + "]]]" }
}

/* Example Usage

> mill showNamed foo[__].suffix
{
  "foo[2.10].suffix": "2.10",
  "foo[2.11].suffix": "2.11",
  "foo[2.12].suffix": "2.12"
}
> mill showNamed bar[__].suffix
{
  "bar[2.10].suffix": "[[[2.10]]]",
  "bar[2.11].suffix": "[[[2.11]]]",
  "bar[2.12].suffix": "[[[2.12]]]"
}

*/