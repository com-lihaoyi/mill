package foo
import scalatags.Text.all.*
import mainargs.{main, Parser}

object Foo {
  def generateHtml(text: String) = {
    h1(text).toString
  }

  @main
  def main(text: String) = {
    println(generateHtml(text))
  }

  def main(args: Array[String]): Unit = Parser(this).runOrExit(args)
}
