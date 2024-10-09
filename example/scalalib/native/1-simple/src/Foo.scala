package foo

import scala.scalanative.libc._
import scala.scalanative.unsafe._
import fansi._

object Foo {
  
  def generateHtml(text: String): CString = {
    val colored = Console.RED + "<h1>" + text + "</h1>" + Console.RESET + "\n"

    implicit val z: Zone = Zone.open
    val cResult = toCString(colored)
    z.close()
    cResult
  }

  def main(args: Array[String]): Unit = {
    val text = args(0)
    stdio.printf(generateHtml(text))  // Now printing the result
  }
}

