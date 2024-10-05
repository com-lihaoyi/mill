package bar
import scalatags.Text.all._
import scala.scalanative.unsafe._

object Bar {
  def main(args: Array[String]): Unit = {
    println("Running HelloWorld function")
    val result = HelloWorldBar.generateHtml(args(0))
    println(("Bar value:" + result)
    println("Done...)
  }
}

// Define the external module, the C library containing our function "generateHtml"
@extern
@link("HelloWorldBar")
// Arbitrary object name
object HelloWorldBar {
  // Name and signature of C function
  def generateHtml(str: CString): CString = extern
}
