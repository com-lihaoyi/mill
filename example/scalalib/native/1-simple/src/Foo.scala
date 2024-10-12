package foo

import scala.scalanative.libc._
import scala.scalanative.unsafe._
import mainargs.{main, ParserForMethods}
import fansi._

object Foo {
  
  def generateHtml(text: String): CString = {
    val colored = Console.RED + "<h1>" + text + "</h1>" + Console.RESET + "\n"

    implicit val z: Zone = Zone.open
    val cResult = toCString(colored)
    z.close()
    cResult
  }

  @main
  def main(text: String) = {
    stdio.printf(generateHtml(text))
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
