package foo

import scala.scalanative.libc.*
import scala.scalanative.unsafe.*
import mainargs.{main, ParserForMethods}

object Foo {

  def generateHtml(text: String)(using Zone) = {
    val html = "<h1>" + text + "</h1>\n"

    val cResult = toCString(html)
    cResult

  }

  @main
  def main(text: String) = Zone {
    stdio.printf(generateHtml(text))
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
