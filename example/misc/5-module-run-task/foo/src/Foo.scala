package foo
import mainargs.{main, ParserForMethods, arg}
object Foo {
  val value = "hello"

  def main(args: Array[String]): Unit = {
    println("Foo.value: " + Foo.value)
  }
}
