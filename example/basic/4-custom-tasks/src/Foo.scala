package foo
import scalatags.Text.all._
import mainargs.{main, ParserForMethods}
object Foo {
  @main
  def main(text: String): Unit = {
    val value = h1(text)
    println("value: " + value)
    println("MyDeps.value: " + MyDeps.value)
    println("my.line.count: " + sys.props("my.line.count"))
  }

  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
}
