package bar

import scala.scalanative.libc._
import scala.scalanative.unsafe._

object Bar {
  def main(args: Array[String]): Unit = {
    println("Running HelloWorld function")
    implicit val z: Zone = Zone.open
    val result = toCString(args(0))
    HelloWorldBar.generateHtml(result)
    z.close()
    stdio.printf(c"Bar value: %s\n", args(0))
    println("Done...")
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

