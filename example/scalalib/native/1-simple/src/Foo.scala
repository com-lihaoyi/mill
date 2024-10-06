package foo

import scala.scalanative.unsafe._
import scala.scalanative.libc._
import scalatags.Text.all._
import mainargs.{main, ParserForMethods}


object Foo {
  def generateHtml(text: CString) = {
    h1(text).toCString
  }

  @main
  def main(text: CString) = {
   stdio.printf(generateHtml(text))
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
