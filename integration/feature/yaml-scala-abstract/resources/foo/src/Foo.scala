package foo

private[foo] class Bar
class Qux(bar: Bar)
object Foo {
  def main(args: Array[String]): Unit = {
    println(new Qux(new Bar()))
  }
}
