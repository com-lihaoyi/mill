package foo

import utest._
import scala.scalanative.unsafe._

object HelloWorldTest extends TestSuite {
  val tests = Tests {
    test("reverseString should reverse a C string correctly") {
      val input = c"Hello world!"
      val expected = c"!dlrow olleH"

      val reversed = HelloWorld.reverseString(input)

      // Check if the reversed string matches the expected result
      assert(strcmp(reversed, expected) == 0)
    }
  }
}
