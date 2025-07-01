package bar.qux
import scalatags.Text.all.*
object BarQux {
  def printText(text: String): Unit = {
    val value = p("world")
    println("BarQux.value: " + value)
  }
  def main(args: Array[String]) = printText(args(0))
}
