package foo

import scala.scalanative.unsafe._

object Bar {
  def main(args: Array[String]): Unit = {
    println("Running HelloWorld function")
    val result = HelloWorld.generateHtml(c"Hello")
    println(("Bar value:" + result)
    println("Done...)
  }
}

// Define the external module, the C library containing our function "generateHtml"
@extern
@link("HelloWorld")
// Arbitrary object name
object HelloWorld {
  // Name and signature of C function
  def generateHtml(str: CString): CString = extern
}
