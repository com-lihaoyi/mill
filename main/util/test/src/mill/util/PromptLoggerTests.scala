package mill.util

import mill.api.SystemStreams
import mill.main.client.ProxyStream
import utest._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
object PromptLoggerTests extends TestSuite {

  def setup(now: () => Long, terminfoPath: os.Path) = {
    val baos = new ByteArrayOutputStream()
    val baosOut = new PrintStream(new ProxyStream.Output(baos, ProxyStream.OUT))
    val baosErr = new PrintStream(new ProxyStream.Output(baos, ProxyStream.ERR))
    val promptLogger = new PromptLogger(
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
    ) {
      // For testing purposes, wait till the system is quiescent before re-printing
      // the prompt, to try and keep the test executions deterministics
      override def refreshPrompt(ending: Boolean = false): Unit = {
        streamsAwaitPumperEmpty()
        super.refreshPrompt(ending)
      }
    }
    val prefixLogger = new PrefixLogger(promptLogger, Seq("1"))
    (baos, promptLogger, prefixLogger)
  }

  def check(
      promptLogger: PromptLogger,
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
      // These tests seem flaky on windows but not sure why
      if (!Util.windowsPlatform) {
        var now = 0L

        val (baos, promptLogger, prefixLogger) = setup(() => now, os.temp())

        promptLogger.setPromptHeaderPrefix("123/456")
        promptLogger.setPromptLine(Seq("1"), "/456", "my-task")

        now += 10000

        prefixLogger.outputStream.println("HELLO")

        promptLogger.refreshPrompt()

        prefixLogger.outputStream.println("WORLD")

        promptLogger.removePromptLine(Seq("1"))

        now += 10000
        promptLogger.refreshPrompt()
        now += 10000
        promptLogger.close()

        check(promptLogger, baos, width = 999 /*log file has no line wrapping*/ )(
          "========================================================== TITLE =====================================================",
          "======================================================================================================================",
          // Make sure that the first time a prefix is reported,
          // we print the verbose prefix along with the ticker string
          "[1/456] my-task",
          // Further `println`s come with the prefix
          "[1] HELLO",
          // Calling `refreshPrompt()` prints the header with the given `globalTicker` without
          // the double space prefix (since it's non-interactive and we don't need space for a cursor),
          // the time elapsed, the reported title and ticker, the list of active tickers, followed by the
          // footer
          "[123/456] ================================================ TITLE ================================================= 10s",
          "[1] my-task 10s",
          "======================================================================================================================",
          "[1] WORLD",
          // Calling `refreshPrompt()` after closing the ticker shows the prompt without
          // the ticker in the list, with an updated time elapsed
          "[123/456] ================================================ TITLE ================================================= 20s",
          "======================================================================================================================",
          // Closing the prompt prints the prompt one last time with an updated time elapsed
          "[123/456] ================================================ TITLE ================================================= 30s",
          "======================================================================================================================",
          ""
        )
      }
    }

    test("interactive") {
      if (!Util.windowsPlatform) {
        var now = 0L
        val (baos, promptLogger, prefixLogger) = setup(() => now, os.temp("80 40"))

        promptLogger.setPromptHeaderPrefix("123/456")
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "  [123/456] ========================== TITLE =================================="
        )
        promptLogger.setPromptLine(Seq("1"), "/456", "my-task")

        now += 10000

        prefixLogger.outputStream.println("HELLO")

        promptLogger.refreshPrompt() // Need to call `refreshPrompt()` for prompt to change
        // First time we log with the prefix `[1]`, make sure we print out the title line
        // `[1/456] my-task` so the viewer knows what `[1]` refers to
        check(promptLogger, baos)(
          // Leading newline because we don't have an actual terminal prompt for the initial
          // "up" movement to cancel out the initial "\n"
          "",
          "[1/456] my-task",
          "[1] HELLO",
          "  [123/456] ========================== TITLE ============================== 10s",
          "[1] my-task 10s"
        )

        prefixLogger.outputStream.println("WORLD")
        // Prompt doesn't change, no need to call `refreshPrompt()` for it to be
        // re-rendered below the latest prefixed output. Subsequent log line with `[1]`
        // prefix does not re-render title line `[1/456] ...`
        check(promptLogger, baos)(
          "",
          "[1/456] my-task",
          "[1] HELLO",
          "[1] WORLD",
          "  [123/456] ========================== TITLE ============================== 10s",
          "[1] my-task 10s"
        )

        // Adding new ticker entries doesn't appear immediately,
        // Only after some time has passed do we start displaying the new ticker entry,
        // to ensure it is meaningful to read and not just something that will flash and disappear
        val newPrefixLogger2 = new PrefixLogger(promptLogger, Seq("2"))
        newPrefixLogger2.setPromptLine(Seq("2"), "/456", "my-task-new")
        newPrefixLogger2.errorStream.println("I AM COW")
        newPrefixLogger2.errorStream.println("HEAR ME MOO")

        // For short-lived ticker entries that are removed quickly, they never
        // appear in the prompt at all even though they can run and generate logs
        val newPrefixLogger3 = new PrefixLogger(promptLogger, Seq("3"))
        newPrefixLogger3.setPromptLine(Seq("3"), "/456", "my-task-short-lived")
        newPrefixLogger3.errorStream.println("hello short lived")
        newPrefixLogger3.errorStream.println("goodbye short lived")

        // my-task-new does not appear yet because it is too new
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "",
          "[1/456] my-task",
          "[1] HELLO",
          "[1] WORLD",
          "[2/456] my-task-new",
          "[2] I AM COW",
          "[2] HEAR ME MOO",
          "[3/456] my-task-short-lived",
          "[3] hello short lived",
          "[3] goodbye short lived",
          "  [123/456] ========================== TITLE ============================== 10s",
          "[1] my-task 10s"
        )

        newPrefixLogger3.removePromptLine(Seq("3"))

        now += 1000

        // my-task-new appears by now, but my-task-short-lived has already ended and never appears
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "",
          "[1/456] my-task",
          "[1] HELLO",
          "[1] WORLD",
          "[2/456] my-task-new",
          "[2] I AM COW",
          "[2] HEAR ME MOO",
          "[3/456] my-task-short-lived",
          "[3] hello short lived",
          "[3] goodbye short lived",
          "  [123/456] ========================== TITLE ============================== 11s",
          "[1] my-task 11s",
          "[2] my-task-new 1s"
        )

        promptLogger.removePromptLine(Seq("1"))

        now += 10

        // Even after ending my-task, it remains on the ticker for a moment before being removed
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "",
          "[1/456] my-task",
          "[1] HELLO",
          "[1] WORLD",
          "[2/456] my-task-new",
          "[2] I AM COW",
          "[2] HEAR ME MOO",
          "[3/456] my-task-short-lived",
          "[3] hello short lived",
          "[3] goodbye short lived",
          "  [123/456] ========================== TITLE ============================== 11s",
          "[1] my-task 11s",
          "[2] my-task-new 1s"
        )

        now += 1000

        // When my-task disappears from the ticker, it leaves a blank line for a
        // moment to preserve the height of the prompt
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "",
          "[1/456] my-task",
          "[1] HELLO",
          "[1] WORLD",
          "[2/456] my-task-new",
          "[2] I AM COW",
          "[2] HEAR ME MOO",
          "[3/456] my-task-short-lived",
          "[3] hello short lived",
          "[3] goodbye short lived",
          "  [123/456] ========================== TITLE ============================== 12s",
          "[2] my-task-new 2s",
          ""
        )

        now += 10000

        // Only after more time does the prompt shrink back
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "",
          "[1/456] my-task",
          "[1] HELLO",
          "[1] WORLD",
          "[2/456] my-task-new",
          "[2] I AM COW",
          "[2] HEAR ME MOO",
          "[3/456] my-task-short-lived",
          "[3] hello short lived",
          "[3] goodbye short lived",
          "  [123/456] ========================== TITLE ============================== 22s",
          "[2] my-task-new 12s"
        )
        now += 10000
        promptLogger.close()
        check(promptLogger, baos)(
          "",
          "[1/456] my-task",
          "[1] HELLO",
          "[1] WORLD",
          "[2/456] my-task-new",
          "[2] I AM COW",
          "[2] HEAR ME MOO",
          "[3/456] my-task-short-lived",
          "[3] hello short lived",
          "[3] goodbye short lived",
          "[123/456] ============================ TITLE ============================== 32s",
          ""
        )
      }
    }

    test("sequentialShortLived") {
      if (!Util.windowsPlatform) {
        // Make sure that when we have multiple sequential tasks being run on different threads,
        // we still end up showing some kind of task in progress in the ticker, even though the
        // tasks on each thread are short-lived enough they would not normally get shown if run
        // alone.
        @volatile var now = 0L
        val (baos, promptLogger, prefixLogger) = setup(() => now, os.temp("80 40"))

        promptLogger.setPromptHeaderPrefix("123/456")
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "  [123/456] ========================== TITLE =================================="
        )
        promptLogger.setPromptLine(Seq("1"), "/456", "my-task")

        now += 100

        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "  [123/456] ========================== TITLE =================================="
        )

        promptLogger.removePromptLine(Seq("1"))

        val newTaskThread = new Thread(() => {
          promptLogger.setPromptLine(Seq("2"), "/456", "my-task-new")
          now += 100
          promptLogger.removePromptLine(Seq("2"))
        })
        newTaskThread.start()
        newTaskThread.join()

        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "  [123/456] ========================== TITLE =================================="
        )

        val newTaskThread2 = new Thread(() => {
          promptLogger.setPromptLine(Seq("2"), "/456", "my-task-new")
          now += 100
        })
        newTaskThread2.start()
        newTaskThread2.join()
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "  [123/456] ========================== TITLE ==================================",
          "[2] my-task-new"
        )
      }
    }
    test("detail") {
      if (!Util.windowsPlatform) {
        // Make sure that when we have multiple sequential tasks being run on different threads,
        // we still end up showing some kind of task in progress in the ticker, even though the
        // tasks on each thread are short-lived enough they would not normally get shown if run
        // alone.
        @volatile var now = 0L
        val (baos, promptLogger, prefixLogger) = setup(() => now, os.temp("80 40"))

        promptLogger.setPromptHeaderPrefix("123/456")
        promptLogger.refreshPrompt()

        promptLogger.setPromptLine(Seq("1"), "/456", "my-task")
        prefixLogger.ticker("detail")
        now += 1000
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "  [123/456] ========================== TITLE =============================== 1s",
          "[1] my-task 1s detail"
        )
        prefixLogger.ticker("detail-too-long-gets-truncated-abcdefghijklmnopqrstuvwxyz1234567890")
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "  [123/456] ========================== TITLE =============================== 1s",
          "[1] my-task 1s detail-too-long-gets-truncated...fghijklmnopqrstuvwxyz1234567890"
        )
        promptLogger.removePromptLine(Seq("1"))
        now += 10000
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "  [123/456] ========================== TITLE ============================== 11s"
        )
      }
    }
  }
}
