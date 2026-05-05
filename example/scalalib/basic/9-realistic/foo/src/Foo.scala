package foo
import scalatags.Text.all._
object Foo {
  val value = h1("hello")
  def main(args: Array[String]): Unit = {
    println("foo version " + Version.value)
    println("Foo.value: " + Foo.value)
    println("Bar.value: " + bar.Bar.value)
    println("Qux.value: " + qux.Qux.value)
  }
}
