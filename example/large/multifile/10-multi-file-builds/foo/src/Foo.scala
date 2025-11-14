package foo
import mainargs.{main, Parser, arg}
object Foo {
  val value = "hello"

  @main
  def main(
      @arg(name = "foo-text") fooText: String,
      @arg(name = "bar-qux-text") barQuxText: String
  ): Unit = {
    println("Foo.value: " + Foo.value)
    bar.qux.BarQux.printText(barQuxText)
  }

  def main(args: Array[String]): Unit = Parser(this).runOrExit(args)
}
