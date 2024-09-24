package mill.util

import mill.api.SystemStreams
import mill.main.client.ProxyStream
import utest._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
object MultilinePromptLoggerTests extends TestSuite {

  def setup(now: () => Long, terminfoPath: os.Path) = {
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
      terminfoPath = terminfoPath,
      currentTimeMillis = now,
      autoUpdate = false
    )
    val prefixLogger = new PrefixLogger(promptLogger, "[1]")
    (baos, promptLogger, prefixLogger)
  }

  def check(
      promptLogger: MultilinePromptLogger,
      baos: ByteArrayOutputStream,
      width: Int = 80
  )(expected: String*) = {
    promptLogger.streamsAwaitPumperEmpty()
    val finalBaos = new ByteArrayOutputStream()
    val pumper =
      new ProxyStream.Pumper(new ByteArrayInputStream(baos.toByteArray), finalBaos, finalBaos)
    pumper.run()
    val term = new TestTerminal(width)
    term.writeAll(finalBaos.toString)
    val lines = term.grid
    assert(lines == expected)
  }

  val tests = Tests {
    test("nonInteractive") {
      var now = 0L

      val (baos, promptLogger, prefixLogger) = setup(() => now, os.temp())

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

      check(promptLogger, baos, width = 999 /*log file has no line wrapping*/ )(
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
        "======================================================================================================================"
      )
    }

    test("interactive") {
      var now = 0L
      val (baos, promptLogger, prefixLogger) = setup(() => now, os.temp("80 40"))

      promptLogger.globalTicker("123/456")
      promptLogger.refreshPrompt()
      check(promptLogger, baos)(
        "  123/456 ============================ TITLE ================================= "
      )
      promptLogger.ticker("[1]", "[1/456]", "my-task")

      now += 10000

      prefixLogger.outputStream.println("HELLO")

      promptLogger.refreshPrompt() // Need to call `refreshPrompt()` for prompt to change
      // First time we log with the prefix `[1]`, make sure we print out the title line
      // `[1/456] my-task` so the viewer knows what `[1]` refers to
      check(promptLogger, baos)(
        "[1/456] my-task",
        "[1] HELLO",
        "  123/456 ============================ TITLE ============================== 10s",
        "[1] my-task 10s"
      )

      prefixLogger.outputStream.println("WORLD")
      // Prompt doesn't change, no need to call `refreshPrompt()` for it to be
      // re-rendered below the latest prefixed output. Subsequent log line with `[1]`
      // prefix does not re-render title line `[1/456] ...`
      check(promptLogger, baos)(
        "[1/456] my-task",
        "[1] HELLO",
        "[1] WORLD",
        "  123/456 ============================ TITLE ============================== 10s",
        "[1] my-task 10s"
      )
      val t = new Thread(() => {
        val newPrefixLogger = new PrefixLogger(promptLogger, "[2]")
        newPrefixLogger.ticker("[2]", "[2/456]", "my-task-new")
        newPrefixLogger.errorStream.println("I AM COW")
        newPrefixLogger.errorStream.println("HEAR ME MOO")
      })
      t.start()
      t.join()

      promptLogger.refreshPrompt()
      check(promptLogger, baos)(
        "[1/456] my-task",
        "[1] HELLO",
        "[1] WORLD",
        "[2/456] my-task-new",
        "[2] I AM COW",
        "[2] HEAR ME MOO",
        "  123/456 ============================ TITLE ============================== 10s",
        "[1] my-task 10s",
        "[2] my-task-new "
      )

      promptLogger.endTicker()

      now += 10000
      promptLogger.refreshPrompt()
      check(promptLogger, baos)(
        "[1/456] my-task",
        "[1] HELLO",
        "[1] WORLD",
        "[2/456] my-task-new",
        "[2] I AM COW",
        "[2] HEAR ME MOO",
        "  123/456 ============================ TITLE ============================== 20s",
        "[2] my-task-new 10s"
      )
      now += 10000
      promptLogger.close()
      check(promptLogger, baos)(
        "[1/456] my-task",
        "[1] HELLO",
        "[1] WORLD",
        "[2/456] my-task-new",
        "[2] I AM COW",
        "[2] HEAR ME MOO",
        "123/456 ============================== TITLE ============================== 30s"
      )
    }
  }
}
