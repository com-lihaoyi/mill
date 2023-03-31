package foo
import scalatags.Text.all._
object Foo {
  val value = h1("hello")
  def main(args: Array[String]): Unit = {
    println("Foo.value: " + Foo.value)
    println("MyDeps.value: " + MyDeps.value)
    println("my.line.count: " + sys.props("my.line.count"))
  }
}
