package foo
import scala.scalanative.unsafe._
import mainargs.{main, ParserForMethods, arg}

object Foo {
  @main
  def main(@arg(name = "foo-text") fooText: CString,
           @arg(name = "bar-text") barText: CString): Unit = {
    println("Foo.value: " + HelloWorldFoo.generateHtml(fooText))
    println("Bar.value: " + bar.Bar.generateHtml(barText))
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
