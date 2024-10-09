package foo

import scala.scalanative.libc._
import scala.scalanative.unsafe._
import mainargs.{main, ParserForMethods, arg}

object Foo {
  @main
  def main(@arg(name = "foo-text") fooText: String,
           @arg(name = "bar-text") barText: String): Unit = {

    implicit val z: Zone = Zone.open
    val cFooText = toCString(fooText)
    val cBarText = toCString(barText)
    z.close

    stdio.printf("Foo.value: %s\n", HelloWorldFoo.generateHtml(cFooText))
    stdio.printf("Bar.value: %s\n", bar.Bar.generateHtml(cBarText))
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}

@extern
@link("HelloWorldFoo")
// Arbitrary object name
object HelloWorldFoo {
  // Name and signature of C function
  def generateHtml(str: CString): CString = extern
}
