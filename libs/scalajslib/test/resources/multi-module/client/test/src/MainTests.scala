import utest._
import shared.Utils

object MainTests extends TestSuite {
  def tests: Tests = Tests {
    test("Lib") {
      test("addTwice") {
        assert(
          Lib.addTwice(1, 2) == 6
        )
      }
      test("parse") {
        assert(
          Lib.parse("hello:world") == Seq("hello", "world")
        )
      }
    }
    test("shared") {
      test("add") {
        assert(
          Utils.add(1, 2) == 3
        )
      }
    }
  }
}
