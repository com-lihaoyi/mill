package mill.internal

import mill.api.SystemStreams
import mill.constants.ProxyStream
import utest.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
object PromptLoggerTests extends TestSuite {

  def setup(now: () => Long, terminfoPath: os.Path) = {
    val baos = ByteArrayOutputStream()
    val baosOut = PrintStream(new ProxyStream.Output(baos, ProxyStream.OUT))
    val baosErr = PrintStream(new ProxyStream.Output(baos, ProxyStream.ERR))
    val promptLogger = new PromptLogger(
      colored = false,
      enableTicker = true,
      infoColor = fansi.Attrs.Empty,
      warnColor = fansi.Attrs.Empty,
      errorColor = fansi.Attrs.Empty,
      systemStreams0 = SystemStreams(baosOut, baosErr, System.in),
      debugEnabled = false,
      titleText = "TITLE",
      terminfoPath = terminfoPath,
      currentTimeMillis = now,
      autoUpdate = false,
      chromeProfileLogger = new JsonArrayLogger.ChromeProfile(os.temp())
    ) {
      // For testing purposes, wait till the system is quiescent before re-printing
      // the prompt, to try and keep the test executions deterministic
      override def refreshPrompt(ending: Boolean = false): Unit = {
        streamsAwaitPumperEmpty()
        super.refreshPrompt(ending)
      }
    }
    val prefixLogger = PrefixLogger(promptLogger, Seq("1"))
    (baos, promptLogger, prefixLogger)
  }

  def check(
      promptLogger: PromptLogger,
      baos: ByteArrayOutputStream,
      width: Int = 80
  )(expected: String*) = {
    promptLogger.streamsAwaitPumperEmpty()
    val finalBaos = ByteArrayOutputStream()
    val pumper =
      new ProxyStream.Pumper(ByteArrayInputStream(baos.toByteArray), finalBaos, finalBaos)
    pumper.run()
    val term = TestTerminal(width)
    term.writeAll(finalBaos.toString)
    val lines = term.grid.map(_.stripSuffix("\r"))

    assert(lines == expected)
  }

  val tests = Tests {
    test("nonInteractive") - retry(3) {
      var now = 0L

      val (baos, promptLogger, prefixLogger) = setup(() => now, os.temp())

      promptLogger.prompt.setPromptHeaderPrefix("123/456")
      promptLogger.prompt.setPromptLine(Seq("1"), "/456", "my-task")

      now += 10000

      prefixLogger.streams.out.println("HELLO")

      promptLogger.refreshPrompt()

      prefixLogger.streams.out.println("WORLD")

      promptLogger.prompt.removePromptLine(Seq("1"), "my-task")

      now += 10000
      promptLogger.refreshPrompt()
      now += 10000
      promptLogger.close()

      check(promptLogger, baos, width = 999 /*log file has no line wrapping*/ )(
        "============================== TITLE ==============================",
        // Make sure that the first time a task prints a log line,
        // we print the task name along with the log line
        "1] my-task HELLO",
        // Calling `refreshPrompt()` prints the header with the given `globalTicker` without
        // the double space prefix (since it's non-interactive and we don't need space for a cursor),
        // the time elapsed, the reported title and ticker, the list of active tickers, followed by the
        // footer
        "123/456] ============================== TITLE ============================== 10s",
        "1] my-task 10s",
        "1] WORLD",
        // Calling `refreshPrompt()` after closing the ticker shows the prompt without
        // the ticker in the list, with an updated time elapsed
        "123/456] ============================== TITLE ============================== 20s",
        // Closing the prompt prints the prompt one last time with an updated time elapsed
        "123/456] ============================== TITLE ============================== 30s",
        ""
      )

    }

    test("interactive") - retry(3) {

      var now = 0L
      val (baos, promptLogger, prefixLogger) = setup(() => now, os.temp("80 40"))

      promptLogger.prompt.setPromptHeaderPrefix("123/456")
      promptLogger.refreshPrompt()
      check(promptLogger, baos)(
        "123/456] ============================== TITLE ==============================",
        ""
      )
      promptLogger.prompt.setPromptLine(Seq("1"), "/456", "my-task")

      now += 10000

      prefixLogger.streams.out.println("HELLO")

      promptLogger.refreshPrompt() // Need to call `refreshPrompt()` for prompt to change
      // First time we log with the prefix `[1]`, make sure we print out the title line
      // `[1/456] my-task` so the viewer knows what `[1]` refers to
      check(promptLogger, baos)(
        "1] my-task HELLO",
        "123/456] ============================== TITLE ============================= 10s",
        "1] my-task 10s",
        ""
      )

      prefixLogger.streams.out.println("WORLD")
      // Prompt doesn't change, no need to call `refreshPrompt()` for it to be
      // re-rendered below the latest prefixed output. Subsequent log line with `[1]`
      // prefix does not re-render title line `[1/456] ...`
      check(promptLogger, baos)(
        "1] my-task HELLO",
        "1] WORLD",
        "123/456] ============================== TITLE ============================= 10s",
        "1] my-task 10s",
        ""
      )

      // Adding new ticker entries doesn't appear immediately,
      // Only after some time has passed do we start displaying the new ticker entry,
      // to ensure it is meaningful to read and not just something that will flash and disappear
      val newPrefixLogger2 = PrefixLogger(promptLogger, Seq("2"))
      newPrefixLogger2.prompt.setPromptLine(Seq("2"), "/456", "my-task-new")
      newPrefixLogger2.streams.err.println("I AM COW")
      newPrefixLogger2.streams.err.println("HEAR ME MOO")

      // For short-lived ticker entries that are removed quickly, they never
      // appear in the prompt at all even though they can run and generate logs
      val newPrefixLogger3 = PrefixLogger(promptLogger, Seq("3"))
      newPrefixLogger3.prompt.setPromptLine(Seq("3"), "/456", "my-task-short-lived")
      newPrefixLogger3.streams.err.println("hello short lived")
      newPrefixLogger3.streams.err.println("goodbye short lived")

      // my-task-new does not appear yet because it is too new
      promptLogger.refreshPrompt()
      check(promptLogger, baos)(
        "1] my-task HELLO",
        "1] WORLD",
        "2] my-task-new I AM COW",
        "2] HEAR ME MOO",
        "3] my-task-short-lived hello short lived",
        "3] goodbye short lived",
        "123/456] ============================== TITLE ============================= 10s",
        "1] my-task 10s",
        ""
      )

      newPrefixLogger3.prompt.removePromptLine(Seq("3"), "my-task-short-lived")

      now += 1000

      // my-task-new appears by now, but my-task-short-lived has already ended and never appears
      promptLogger.refreshPrompt()
      check(promptLogger, baos)(
        "1] my-task HELLO",
        "1] WORLD",
        "2] my-task-new I AM COW",
        "2] HEAR ME MOO",
        "3] my-task-short-lived hello short lived",
        "3] goodbye short lived",
        "123/456] ============================== TITLE ============================= 11s",
        "1] my-task 11s",
        "2] my-task-new 1s",
        ""
      )

      promptLogger.prompt.removePromptLine(Seq("1"), "my-task")

      now += 10

      // Even after ending my-task, it remains on the ticker for a moment before being removed
      promptLogger.refreshPrompt()
      check(promptLogger, baos)(
        "1] my-task HELLO",
        "1] WORLD",
        "2] my-task-new I AM COW",
        "2] HEAR ME MOO",
        "3] my-task-short-lived hello short lived",
        "3] goodbye short lived",
        "123/456] ============================== TITLE ============================= 11s",
        "1] my-task 11s",
        "2] my-task-new 1s",
        ""
      )

      now += 1000

      // When my-task disappears from the ticker, it leaves a blank line for a
      // moment to preserve the height of the prompt
      promptLogger.refreshPrompt()
      check(promptLogger, baos)(
        "1] my-task HELLO",
        "1] WORLD",
        "2] my-task-new I AM COW",
        "2] HEAR ME MOO",
        "3] my-task-short-lived hello short lived",
        "3] goodbye short lived",
        "123/456] ============================== TITLE ============================= 12s",
        "2] my-task-new 2s",
        "",
        ""
      )

      now += 10000

      // Only after more time does the prompt shrink back
      promptLogger.refreshPrompt()
      check(promptLogger, baos)(
        "1] my-task HELLO",
        "1] WORLD",
        "2] my-task-new I AM COW",
        "2] HEAR ME MOO",
        "3] my-task-short-lived hello short lived",
        "3] goodbye short lived",
        "123/456] ============================== TITLE ============================= 22s",
        "2] my-task-new 12s",
        ""
      )
      now += 10000
      promptLogger.close()
      check(promptLogger, baos)(
        "1] my-task HELLO",
        "1] WORLD",
        "2] my-task-new I AM COW",
        "2] HEAR ME MOO",
        "3] my-task-short-lived hello short lived",
        "3] goodbye short lived",
        "123/456] ============================== TITLE ============================= 32s",
        ""
      )
    }

    test("detail") {
      // Make sure that when we have multiple sequential tasks being run on different threads,
      // we still end up showing some kind of task in progress in the ticker, even though the
      // tasks on each thread are short-lived enough they would not normally get shown if run
      // alone.
      @volatile var now = 0L
      val (baos, promptLogger, prefixLogger) = setup(() => now, os.temp("80 40"))

      promptLogger.prompt.setPromptHeaderPrefix("123/456")
      promptLogger.refreshPrompt()

      promptLogger.prompt.setPromptLine(Seq("1"), "/456", "my-task")
      prefixLogger.ticker("detail")
      now += 1000
      promptLogger.refreshPrompt()
      check(promptLogger, baos)(
        "123/456] ============================== TITLE ============================== 1s",
        "1] my-task 1s detail",
        ""
      )
      prefixLogger.ticker("detail-too-long-gets-truncated-abcdefghijklmnopqrstuvwxyz1234567890")
      promptLogger.refreshPrompt()
      check(promptLogger, baos)(
        "123/456] ============================== TITLE ============================== 1s",
        "1] my-task 1s detail-too-long-gets-truncated-...fghijklmnopqrstuvwxyz1234567890",
        ""
      )
      promptLogger.prompt.removePromptLine(Seq("1"), "my-task")
      now += 10000
      promptLogger.refreshPrompt()
      check(promptLogger, baos)(
        "123/456] ============================== TITLE ============================= 11s",
        ""
      )
    }
  }
}
