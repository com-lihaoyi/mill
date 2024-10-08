package foo
import scala.scalanative.libc._
import scala.scalanative.unsafe._
import fansi._

object Foo {
  
  def generateHtml(text: String) = {
    val colored = fansi.Color.Green(text)
    println(colored)
    implicit val z: Zone = Zone.open
    stdio.printf(c"<h1>%s</h1>\n", toCString(text))
    z.close()
  }

  def main(args: Array[String]): Unit = {
    val text = args(0)
    generateHtml(text)
  }
}
