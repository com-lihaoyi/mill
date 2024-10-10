package bar

import scala.scalanative.libc._
import scala.scalanative.unsafe._

object Bar {
  def main(args: Array[String]): Unit = {
    println("Running HelloWorld function")
    implitct val z: Zone = Zone.open
    val input = toCString(ags(0))
    val result = HelloWorldBar.generateHtml(input)
    stdio.printf(c"Bar value: %s", input)
    z.close()
    stdio.printf("Done...")
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
