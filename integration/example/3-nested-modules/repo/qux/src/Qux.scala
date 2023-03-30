package qux
import scalatags.Text.all._
object Qux {
  val value = p("today")

  def main(args: Array[String]): Unit = {
    println("Foo.value: " + foo.Foo.value)
    println("Bar.value: " + bar.Bar.value)
    println("Qux.value: " + qux.Qux.value)
  }
}
