package foo
import scalatags.Text.all._
import mainargs.{main, ParserForMethods, arg}

object Foo {
  @main
  def main(@arg(name = "bar-text") barText: String,
           @arg(name = "qux-text") quxText: String,
           @arg(name = "foo-text") fooText: String): Unit = {
    foo.qux.Qux.main(barText, quxText)

    val value = p(fooText)
    println("Foo.value: " + value)
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
