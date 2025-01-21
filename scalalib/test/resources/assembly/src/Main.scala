package ultra

import scalatags.Text.all._
import mainargs.{main, ParserForMethods}

object Main {
  def generateHtml(text: String) = {
    h1(text).toString
  }

  @main
  def main(text: String) = {
    println(generateHtml(text))
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
