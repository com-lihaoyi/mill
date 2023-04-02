package foo
import scalatags.Text.all._
object Foo {
  def main(args: Array[String]): Unit = {
    println("Bar.value: " + bar.Bar.value)
    val string = """{"i": "am", "cow": "hear", "me": "moo"}"""
    println("Foo.main: " + FooPlatformSpecific.parseJsonGetKeys(string).map(p(_)))
  }
}
