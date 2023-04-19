package foo
import mainargs.{main, ParserForMethods, arg}
object Foo {
  val value = "hello"

  @main
  def main(@arg(name = "foo-text") fooText: String,
           @arg(name = "bar-text") barText: String): Unit = {
    println("Foo.value: " + Foo.value)
    bar.Bar.printText(barText)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
