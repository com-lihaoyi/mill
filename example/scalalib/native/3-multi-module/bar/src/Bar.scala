package bar

import scala.scalanative.libc.*
import scala.scalanative.unsafe.*

object Bar {
  def main(args: Array[String]): Unit = Zone {
    println("Running HelloWorld function")
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
