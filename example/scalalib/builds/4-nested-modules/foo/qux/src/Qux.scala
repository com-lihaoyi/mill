package foo.qux
import scalatags.Text.all._
import mainargs.{main, ParserForMethods, arg}

object Qux {
  @main
  def main(@arg(name = "bar-text") barText: String,
           @arg(name = "qux-text") quxText: String): Unit = {
    foo.bar.Bar.main(barText)

    val value = p(quxText)
    println("Qux.value: " + value)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
