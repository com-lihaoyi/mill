package foo
import mainargs.{main, ParserForMethods, arg}
object Foo {
  @main
  def main(text: String): Unit = {
    println(bar.Bar.generateHtml(text))
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
