package baz
import scalatags.Text.all._
import mainargs.{main, ParserForMethods, arg}

object Baz {
  @main
  def main(@arg(name = "bar-text") barText: String,
           @arg(name = "qux-text") quxText: String,
           @arg(name = "foo-text") fooText: String,
           @arg(name = "baz-text") bazText: String): Unit = {
    foo.Foo.main(barText, quxText, fooText)

    val value = p(bazText)
    println("Baz.value: " + value)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
