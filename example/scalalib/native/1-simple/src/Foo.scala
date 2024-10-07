package foo
import scala.scalanative.libc._
import scala.scalanative.unsafe._

object Foo {
  
  def generateHtml(text: CString) = {
    stdio.printf(c"<h1>%s</h1>\n", text)
  }

  def main(args: Array[String]): Unit = {
    implicit val z: Zone = Zone.open
    val text = args(0)
    generateHtml(toCString(text))
    z.close()
  }
}

