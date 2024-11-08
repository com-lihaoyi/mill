package foo

import scala.scalanative.libc._
import scala.scalanative.unsafe._
import fansi._

object Foo {

  def generateHtml(text: String) (using Zone) = {
    val colored = Console.RED + "<h1>" + text + "</h1>" + Console.RESET

    val cResult = toCString(colored)
    cResult
  }
  
  def main(args: Array[String]): Unit = Zone {
    val value = generateHtml("hello")
    stdio.printf(c"Value: %s\n", value)
  }
}

