package foo

import utest.*
import scala.scalanative.unsafe.*
import scala.scalanative.libc.stdlib

object HelloWorldTest extends TestSuite {
  val tests = Tests {
    test("reverseString should reverse a C string correctly") {
      val expected = "!dlroW olleH"
      val result = HelloWorld.reverseString(c"Hello World!")

      // Check if the reversed string matches the expected result
      assert(fromCString(result) == expected)

      stdlib.free(result) // Free memory after the test
    }
  }
}
