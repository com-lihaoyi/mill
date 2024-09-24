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
  }
}
