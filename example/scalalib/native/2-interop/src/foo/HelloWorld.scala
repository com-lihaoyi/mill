package foo
import scala.scalanative.unsafe._

object Main {
  def main(args: Array[String]): Unit = {
    print("Running HelloWorld function\n")
    val input = c"Hello, World!!\n"
    val reversed = HelloWorld.reverseString(input)
    println(c"Reversed: %s", reversed)
    println("Done...")
  }
}

// Define the external module, the C library containing our function "reverseString"
@extern
@link("HelloWorld")
// Arbitrary object name
object HelloWorld {
  // Name and signature of C function
  def reverseString(str: CString): CString = extern
}
