package foo
import mainargs.{main, Parser, arg}
object Foo {
  @main
  def main(text: String): Unit = {
    println(bar.Bar.generateHtml(text))
  }

  def main(args: Array[String]): Unit = Parser(this).runOrExit(args)
}
