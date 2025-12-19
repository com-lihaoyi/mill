package foo
object Foo {
  def main(args: Array[String]): Unit = {
    println(greet("World"))
  }
  def greet(name: String): String = s"Hello $name"
}
