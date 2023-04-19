package bar
import scalatags.Text.all._
import mainargs.{main, ParserForMethods}
object Bar {
  @main
  def main(text: String): Unit = {
    val value = p("world")
    println("Bar.value: " + value)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
