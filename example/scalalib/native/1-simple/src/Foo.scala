package foo

import scala.scalanative.libc._
import scala.scalanative.unsafe._
import mainargs.{main, ParserForMethods}

object Foo {
  
  def generateHtml(text: String): CString = {
    val html = "<h1>" + text + "</h1>\n"

    implicit val z: Zone = Zone.open()
    val cResult = toCString(html)
    cResult
  
  }

  @main
  def main(text: String) = {
    stdio.printf(generateHtml(text))
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}

