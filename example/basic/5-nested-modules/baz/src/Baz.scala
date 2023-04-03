package baz
import scalatags.Text.all._
object Baz {
  val value = p("today")

  def main(args: Array[String]): Unit = {
    println("Bar.value: " + foo.bar.Bar.value)
    println("Qux.value: " + foo.qux.Qux.value)
    println("Baz.value: " + value)
  }
}
