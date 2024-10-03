package foo

object Main {
  def main(args: Array[String]): Unit = {
    print("Running HelloWorld function\n")
    HelloWorld.printString(c"Hello world!!\n")
    print("Done...\n")
  }
}

// Define the external module, the C library containing our function "printString"
@extern
@link("HelloWorld")
// Arbitrary object name
object HelloWorld {
  // Name and signature of C function
  def printString(str: CString): Unit = extern
}
