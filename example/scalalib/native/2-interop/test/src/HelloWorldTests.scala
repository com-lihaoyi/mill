package foo

import utest._
import scala.scalanative.unsafe._

object HelloWorldTest extends TestSuite {
  val tests = Tests {
    test("reverseString should reverse a C string correctly") {
      val expected = "!dlrow olleH"

      val result = HelloWorld.reverseString(c"Hello World!")

      // Check if the reversed string matches the expected result
      assert(fromCString(result) == expected)
      fromCString(result)
    }
  }
}

