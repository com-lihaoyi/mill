package foo

import scala.scalanative.libc._
import scala.scalanative.unsafe._
import fansi._

object Foo {

  def generateHtml(text: String): CString = {
    val colored = Console.RED + "<h1>" + text + "</h1>" + Console.RESET

    implicit val z: Zone = Zone.open()
    val cResult = toCString(colored)
    cResult
  }
  
  val value = generateHtml("hello")

  def main(args: Array[String]): Unit = {
    stdio.printf(c"Foo.value: %s\n", Foo.value)
  }
}

