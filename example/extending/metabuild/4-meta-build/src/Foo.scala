package foo
import scalatags.Text.all.*
object Foo {
  def main(args: Array[String]): Unit = {
    println("Build-time HTML snippet: " + os.read(os.resource / "snippet.txt"))
    println("Run-time HTML snippet: " + p("world"))
  }
}
