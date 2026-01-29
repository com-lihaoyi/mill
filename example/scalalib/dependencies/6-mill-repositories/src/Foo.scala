package foo

import mainargs.{main, arg, ParserForMethods, Flag}

object Foo {
  @main
  def run(@arg(name = "text") text: String) = {
    println(s"text: $text, Scala version: ${scala.util.Properties.versionString}")
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
