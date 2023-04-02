package bar
import scalatags.Text.all._
object Bar {
  val value = p("world")

  def main(args: Array[String]): Unit = {
    println("Bar.value: " + bar.Bar.value)
  }
}
