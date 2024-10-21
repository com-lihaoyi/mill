package foo
import scala.scalanative.libc._
import scala.scalanative.unsafe._

object Main {
  def main(args: Array[String]): Unit = {
    println("Running HelloWorld function")
    stdio.printf(c"Reversed: %s\n", HelloWorld.reverseString(c"Hello, World!"))
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

