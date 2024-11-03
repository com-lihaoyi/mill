package foo

import scala.scalanative.libc._
import scala.scalanative.unsafe._
import mainargs.{main, ParserForMethods, arg}

object Foo {
  @main
  def main(@arg(name = "foo-text") fooText: String,
           @arg(name = "bar-text") barText: String): Unit = {

    implicit val z: Zone = Zone.open()
    val cFooText = toCString(fooText)
    val cBarText = toCString(barText)

    stdio.printf(c"Foo.value: The vowel density of '%s' is %d\n", cFooText, HelloWorldFoo.vowelDensity(cFooText))
    stdio.printf(c"Bar.value: The string length of '%s' is %d\n", cBarText, bar.HelloWorldBar.stringLength(cBarText))
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}

@extern
// Arbitrary object name
object HelloWorldFoo {
  // Name and signature of C function
  def vowelDensity(str: CString): CInt = extern
}


