package bar

import scala.scalanative.libc._
import scala.scalanative.unsafe._

object Bar {
  def main(args: Array[String]): Unit = {
    println("Running HelloWorld function")
    implicit val z: Zone = Zone.open()
    val result = toCString(args(0))
    val barValue = HelloWorldBar.stringLength(result)
    stdio.printf(c"Bar value: Argument length is %i\n", barValue)
    println("Done...")
  }
}

// Define the external module, the C library containing our function "stringLength"
@extern
// Arbitrary object name
object HelloWorldBar {
  // Name and signature of C function
  def stringLength(str: CString): CInt = extern
}


