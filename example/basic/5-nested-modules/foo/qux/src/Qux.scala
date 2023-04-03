package foo.qux
import scalatags.Text.all._
object Qux {
  val value = h1("hello")

  def main(args: Array[String]): Unit = {
    println("Qux.value: " + Qux.value)
    println("Bar.value: " + foo.bar.Bar.value)
  }
}
