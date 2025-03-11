package foo

import scala.scalanative.libc._
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

object Main {
  def main(args: Array[String]): Unit = {
    println("Running HelloWorld function")
    val reversedStr = HelloWorld.reverseString(c"Hello, World!")
    println("Reversed: " + fromCString(reversedStr))
    stdlib.free(reversedStr) // Free the allocated memory
    println("Done...")
  }
}

// Define the external module, the C library containing our function "reverseString"
@extern
object HelloWorld {
  def reverseString(str: CString): CString = extern
}
