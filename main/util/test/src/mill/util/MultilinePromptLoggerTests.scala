package mill.util

import mill.api.SystemStreams
import mill.main.client.ProxyStream
import utest._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
object MultilinePromptLoggerTests extends TestSuite {

  val tests = Tests {
    test("nonInteractive") {
      var now = 0L

      val baos = new ByteArrayOutputStream()
      val baosOut = new PrintStream(new ProxyStream.Output(baos, ProxyStream.OUT))
      val baosErr = new PrintStream(new ProxyStream.Output(baos, ProxyStream.ERR))
      val promptLogger = new MultilinePromptLogger(
        colored = false,
        enableTicker = true,
        infoColor = fansi.Attrs.Empty,
        errorColor = fansi.Attrs.Empty,
        systemStreams0 = new SystemStreams(baosOut, baosErr, System.in),
        debugEnabled = false,
        titleText = "TITLE",
        terminfoPath = os.temp(),
        currentTimeMillis = () => now
      )
      val prefixLogger = new PrefixLogger(promptLogger, "[1]")

      promptLogger.globalTicker("123/456")
      promptLogger.ticker("[1]", "[1/456]", "my-task")

      now += 10000

      prefixLogger.outputStream.println("HELLO")

      promptLogger.refreshPrompt()

      prefixLogger.outputStream.println("WORLD")

      promptLogger.endTicker()

      now += 10000
      promptLogger.refreshPrompt()
      now += 10000
      promptLogger.close()

      val finalBaos = new ByteArrayOutputStream()
      val pumper = new ProxyStream.Pumper(new ByteArrayInputStream(baos.toByteArray), finalBaos, finalBaos)
      pumper.run()
      val lines = finalBaos.toString.linesIterator.toSeq
      val expected = Seq(
        // Make sure that the first time a prefix is reported,
        // we print the verbose prefix along with the ticker string
        "[1/456] my-task",
        // Further `println`s come with the prefix
        "[1] HELLO",
        // Calling `refreshPrompt()` prints the header with the given `globalTicker` without
        // the double space prefix (since it's non-interactive and we don't need space for a cursor),
        // the time elapsed, the reported title and ticker, the list of active tickers, followed by the
        // footer
        "123/456 ================================================== TITLE ================================================= 10s",
        "[1] my-task 10s",
        "======================================================================================================================",
        "[1] WORLD",
        // Calling `refreshPrompt()` after closing the ticker shows the prompt without
        // the ticker in the list, with an updated time elapsed
        "123/456 ================================================== TITLE ================================================= 20s",
        "======================================================================================================================",
        // Closing the prompt prints the prompt one last time with an updated time elapsed
        "123/456 ================================================== TITLE ================================================= 30s",
        "======================================================================================================================",
      )

      assert(lines == expected)
    }
    test("testTerminal"){
      test("wrap") {
        val t = new TestTerminal(width = 10)
        t.writeAll("1234567890abcdef")
        t.grid ==> Seq(
          "1234567890",
          "abcdef"
        )
      }
      test("newline") {
        val t = new TestTerminal(width = 10)
        t.writeAll("12345\n67890")
        t.grid ==> Seq(
          "12345",
          "67890"
        )
      }
      test("wrapNewline") {
        val t = new TestTerminal(width = 10)
        t.writeAll("1234567890\nabcdef")
        t.grid ==> Seq(
          "1234567890",
          "abcdef"
        )
      }
      test("wrapNewline2") {
        val t = new TestTerminal(width = 10)
        t.writeAll("1234567890\n\nabcdef")
        t.grid ==> Seq(
          "1234567890",
          "",
          "abcdef"
        )
      }
      test("up") {
        val t = new TestTerminal(width = 15)
        t.writeAll(s"1234567890\nabcdef${AnsiNav.up(1)}X")
        t.grid ==> Seq(
          "123456X890",
          "abcdef",
        )
      }
      test("left") {
        val t = new TestTerminal(width = 15)
        t.writeAll(s"1234567890\nabcdef${AnsiNav.left(3)}X")
        t.grid ==> Seq(
          "1234567890",
          "abcXef",
        )
      }
      test("upLeftClearLine") {
        val t = new TestTerminal(width = 15)
        t.writeAll(s"1234567890\nabcdef${AnsiNav.up(1)}${AnsiNav.left(3)}X${AnsiNav.clearLine(0)}")
        t.grid ==> Seq(
          "123X",
          "abcdef",
        )
      }
      test("upLeftClearScreen") {
        val t = new TestTerminal(width = 15)
        t.writeAll(s"1234567890\nabcdef${AnsiNav.up(1)}${AnsiNav.left(3)}X${AnsiNav.clearScreen(0)}")
        t.grid ==> Seq("123X")
      }
      test("wrapUpClearLine") {
        val t = new TestTerminal(width = 10)
        t.writeAll(s"1234567890abcdef${AnsiNav.up(1)}${AnsiNav.left(3)}X${AnsiNav.clearLine(0)}")
        t.grid ==> Seq(
          "123X",
          "abcdef",
        )
      }
      test("wrapUpClearScreen") {
        val t = new TestTerminal(width = 10)
        t.writeAll(s"1234567890abcdef${AnsiNav.up(1)}${AnsiNav.left(3)}X${AnsiNav.clearScreen(0)}")
        t.grid ==> Seq("123X")
      }
    }
  }
}

class TestTerminal(width: Int){
  var grid = collection.mutable.Buffer("")
  var cursorX = 0
  var cursorY = 0

  def writeAll(data0: String): Unit = {

    def rec(data: String): Unit = data match{ // pprint.log((grid, cursorX, cursorY, data.head))
      case "" => // end
      case s"\u001b[${n}A$rest" => // up
        cursorY = math.max(cursorY - n.toInt, 0)
        rec(rest)

      case s"\u001b[${n}B$rest" => // down
        cursorY = math.min(cursorY + n.toInt, grid.size)
        rec(rest)

      case s"\u001b[${n}C$rest" => // right
        cursorX = math.min(cursorX + n.toInt, width)
        rec(rest)

      case s"\u001b[${n}D$rest" => // left
        cursorX = math.max(cursorX - n.toInt, 0)
        rec(rest)

      case s"\u001b[${n}J$rest" => // clearscreen
        n match{
          case "0" =>
            grid(cursorY) = grid(cursorY).take(cursorX)
            grid = grid.take(cursorY + 1)
            rec(rest)
        }

      case s"\u001b[${n}K$rest" => // clearline
        n match{
          case "0" =>
            grid(cursorY) = grid(cursorY).take(cursorX)
            rec(rest)
        }

      case normal => // normal text
        if (normal.head == '\n'){
          cursorX = 0
          if (cursorY >= grid.length) grid.append("")
          cursorY += 1
        }else {
          if (cursorX == width){
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

