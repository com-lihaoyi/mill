package foo
import scalatags.Text.all._
import mainargs.{main, ParserForMethods}
object Foo {
  @main
  def main(text: String): Unit = {
    val value = h1(text)
    println("Foo.value: " + value)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
