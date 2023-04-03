package qux
import scalatags.Text.all._
import mainargs.{main, ParserForMethods, arg}
object Qux {


  @main
  def main(@arg(name="foo-text") fooText: String,
           @arg(name="foo-text") barText: String,
           @arg(name="foo-text") quxText: String): Unit = {
    foo.Foo.main(text)
    bar.Bar.main(text)

    val value = p(text)
    println("Qux.value: " + value)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
