package qux
import scalatags.Text.all._
object Qux {
  def main(args: Array[String]): Unit = {
    println("Bar.value: " + bar.Bar.value)
    val string = """{"i": "am", "cow": "hear", "me": "moo"}"""
    println("Qux.main: " + QuxPlatformSpecific.parseJsonGetKeys(string).map(p(_)))
  }
}
