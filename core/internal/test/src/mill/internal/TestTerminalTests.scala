package mill.internal

import utest.*

/**
 * Minimal implementation of a terminal emulator that handles ANSI navigation
 * codes, so we can feed in terminal strings and assert that the terminal looks
 * like what it should look like including cleared lines/screens and overridden text
 */
class TestTerminal(width: Int) {
  var grid = collection.mutable.Buffer("")
  var cursorX = 0
  var cursorY = 0

  def writeAll(data0: String): Unit = {

    def rec(data: String): Unit = data match { // pprint.log((grid, cursorX, cursorY, data.head))
      case "" => // end
      case s"\u001b[$rest0" =>
        val num0 = rest0.takeWhile(_.isDigit)
        val n = num0.toInt
        val char = rest0(num0.length)
        val rest = rest0.drop(num0.length + 1)
        char match {
          case 'A' => // up
            cursorY = math.max(cursorY - n.toInt, 0)
            rec(rest)
          case 'B' => // down
            cursorY = math.min(cursorY + n.toInt, grid.size)
            rec(rest)
          case 'C' => // right
            cursorX = math.min(cursorX + n.toInt, width)
            rec(rest)
          case 'D' => // left
            cursorX = math.max(cursorX - n.toInt, 0)
            rec(rest)
          case 'J' => // clearscreen
            n match {
              case 0 =>
                if (cursorY < grid.length) grid(cursorY) = grid(cursorY).take(cursorX)
                grid = grid.take(cursorY + 1)
                rec(rest)
            }
          case 'K' => // clearline
            n match {
              case 0 =>
                grid(cursorY) = grid(cursorY).take(cursorX)
                rec(rest)
            }
        }

      case normal => // normal text
        if (normal.head == '\n') {
          cursorX = 0
          cursorY += 1
          if (cursorY >= grid.length) grid.append("")
        } else {
          if (cursorX == width) {
            cursorX = 0
            cursorY += 1
          }
          if (cursorY >= grid.length) grid.append("")
          grid(cursorY) = grid(cursorY).patch(cursorX, Seq(normal.head), 1)
          if (cursorX < width) {
            cursorX += 1
          } else {
            cursorX = 0
            cursorY += 1
          }

        }
        rec(normal.tail)
    }
    rec(data0)
  }
}

object TestTerminalTests extends TestSuite {

  val tests = Tests {
    test("wrap") {
      val t = TestTerminal(width = 10)
      t.writeAll("1234567890abcdef")
      t.grid ==> Seq(
        "1234567890",
        "abcdef"
      )
    }
    test("newline") {
      val t = TestTerminal(width = 10)
      t.writeAll("12345\n67890")
      t.grid ==> Seq(
        "12345",
        "67890"
      )
    }
    test("trailingNewline") {
      val t = TestTerminal(width = 10)
      t.writeAll("12345\n")
      t.grid ==> Seq(
        "12345",
        ""
      )
    }
    test("wrapNewline") {
      val t = TestTerminal(width = 10)
      t.writeAll("1234567890\nabcdef")
      t.grid ==> Seq(
        "1234567890",
        "abcdef"
      )
    }
    test("wrapNewline2") {
      val t = TestTerminal(width = 10)
      t.writeAll("1234567890\n\nabcdef")
      t.grid ==> Seq(
        "1234567890",
        "",
        "abcdef"
      )
    }
    test("up") {
      val t = TestTerminal(width = 15)
      t.writeAll(s"1234567890\nabcdef${AnsiNav.up(1)}X")
      t.grid ==> Seq(
        "123456X890",
        "abcdef"
      )
    }
    test("left") {
      val t = TestTerminal(width = 15)
      t.writeAll(s"1234567890\nabcdef${AnsiNav.left(3)}X")
      t.grid ==> Seq(
        "1234567890",
        "abcXef"
      )
    }
    test("upLeftClearLine") {
      val t = TestTerminal(width = 15)
      t.writeAll(s"1234567890\nabcdef${AnsiNav.up(1)}${AnsiNav.left(3)}X${AnsiNav.clearLine(0)}")
      t.grid ==> Seq(
        "123X",
        "abcdef"
      )
    }
    test("upLeftClearScreen") {
      val t = TestTerminal(width = 15)
      t.writeAll(s"1234567890\nabcdef${AnsiNav.up(1)}${AnsiNav.left(3)}X${AnsiNav.clearScreen(0)}")
      t.grid ==> Seq("123X")
    }
    test("wrapUpClearLine") {
      val t = TestTerminal(width = 10)
      t.writeAll(s"1234567890abcdef${AnsiNav.up(1)}${AnsiNav.left(3)}X${AnsiNav.clearLine(0)}")
      t.grid ==> Seq(
        "123X",
        "abcdef"
      )
    }
    test("wrapUpClearScreen") {
      val t = TestTerminal(width = 10)
      t.writeAll(s"1234567890abcdef${AnsiNav.up(1)}${AnsiNav.left(3)}X${AnsiNav.clearScreen(0)}")
      t.grid ==> Seq("123X")
    }
  }
}
