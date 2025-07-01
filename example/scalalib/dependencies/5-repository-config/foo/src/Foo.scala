package foo
import scalatags.Text.all.*
import mainargs.{main, ParserForMethods}
object Foo {
  @main
  def main(text: String) = {
    val value = h1(text)
    println(value)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
