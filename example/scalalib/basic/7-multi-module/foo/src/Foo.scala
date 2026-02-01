package foo
import mainargs.{main, Parser, arg}
object Foo {
  @main
  def main(
      @arg(name = "foo-text") fooText: String,
      @arg(name = "bar-text") barText: String
  ): Unit = {
    println("Foo.value: " + fooText)
    println("Bar.value: " + bar.Bar.generateHtml(barText))
  }

  def main(args: Array[String]): Unit = Parser(this).runOrExit(args)
}
