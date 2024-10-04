package foo
import scala.scalanative.unsafe._
import scala.scalanative.libc._

object Main {
  def main(args: Array[String]): Unit = {
    print("Running HelloWorld function\n")
    val input = c"Hello, World!!\n"
    val reversed = HelloWorld.reverseString(input)
    stdlib.printf(c"Reversed: %s", reversed)
    print("Done...\n")
  }
}

// Define the external module, the C library containing our function "printString"
@extern
@link("HelloWorld")
// Arbitrary object name
object HelloWorld {
  // Name and signature of C function
  def reverseString(str: CString): CString = extern
}
