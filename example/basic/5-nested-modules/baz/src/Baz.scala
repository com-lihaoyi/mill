package baz
import scalatags.Text.all._
import mainargs.{main, ParserForMethods, arg}

object Baz {
  @main
  def main(@arg(name = "bar-text") barText: String,
           @arg(name = "qux-text") quxText: String,
           @arg(name = "baz-text") bazText: String): Unit = {
    foo.qux.Qux.main(barText, quxText)

    val value = p(bazText)
    println("Baz.value: " + value)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
