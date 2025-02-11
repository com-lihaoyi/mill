package mill.runner

import utest._

object MillMainTests extends TestSuite {

  private def assertParseErr(result: Result[Int], msg: String): Unit = {
    assert(result.isLeft)
    assert(result.swap.toOption.get.contains(msg))
  }

  def tests: Tests = Tests {

    test("Parsing --jobs/-j flag") {

      test("parse none") {
        assert(MillMain.parseThreadCount(None, 10) == Right(10))
      }

      test("parse int number") {
        assert(MillMain.parseThreadCount(Some("1"), 10) == Right(1))
        assert(MillMain.parseThreadCount(Some("11"), 10) == Right(11))

        assertParseErr(MillMain.parseThreadCount(Some("1.0"), 10), "Failed to find a int number")
        assertParseErr(MillMain.parseThreadCount(Some("1.1"), 10), "Failed to find a int number")
        assertParseErr(MillMain.parseThreadCount(Some("0.1"), 10), "Failed to find a int number")
        assert(MillMain.parseThreadCount(Some("0"), 10) == Right(10))
        assert(MillMain.parseThreadCount(Some("-1"), 10) == Right(1))
      }

      test("parse fraction number") {
        assert(MillMain.parseThreadCount(Some("0.5C"), 10) == Right(5))
        assert(MillMain.parseThreadCount(Some("0.54C"), 10) == Right(5))
        assert(MillMain.parseThreadCount(Some("0.59C"), 10) == Right(5))
        assert(MillMain.parseThreadCount(Some(".5C"), 10) == Right(5))
        assert(MillMain.parseThreadCount(Some("1.0C"), 10) == Right(10))
        assert(MillMain.parseThreadCount(Some("1.5C"), 10) == Right(15))
        assert(MillMain.parseThreadCount(Some("0.09C"), 10) == Right(1))
        assert(MillMain.parseThreadCount(Some("-0.5C"), 10) == Right(1))
        assertParseErr(
          MillMain.parseThreadCount(Some("0.5.4C"), 10),
          "Failed to find a float number before \"C\""
        )
      }

      test("parse subtraction") {
        assert(MillMain.parseThreadCount(Some("C-1"), 10) == Right(9))
        assert(MillMain.parseThreadCount(Some("C-10"), 10) == Right(1))
        assert(MillMain.parseThreadCount(Some("C-11"), 10) == Right(1))

        assertParseErr(
          MillMain.parseThreadCount(Some("C-1.1"), 10),
          "Failed to find a int number after \"C-\""
        )
        assertParseErr(
          MillMain.parseThreadCount(Some("11-C"), 10),
          "Failed to find a float number before \"C\""
        )
      }

      test("parse invalid input") {
        assertParseErr(
          MillMain.parseThreadCount(Some("CCCC"), 10),
          "Failed to find a float number before \"C\""
        )
        assertParseErr(
          MillMain.parseThreadCount(Some("abcdefg"), 10),
          "Failed to find a int number"
        )
      }

    }

  }
}
