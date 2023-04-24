package bar
import scalatags.Text.all._
object Bar {
  def printText(text: String): Unit = {
    val value = p("world")
    println("Bar.value: " + value)
  }
  def main(args: Array[String]) = printText(args(0))
}
