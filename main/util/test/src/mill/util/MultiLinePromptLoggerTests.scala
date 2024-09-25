package mill.util

import mill.api.SystemStreams
import mill.main.client.ProxyStream
import utest._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
object MultiLinePromptLoggerTests extends TestSuite {

  def setup(now: () => Long, terminfoPath: os.Path) = {
    val baos = new ByteArrayOutputStream()
    val baosOut = new PrintStream(new ProxyStream.Output(baos, ProxyStream.OUT))
    val baosErr = new PrintStream(new ProxyStream.Output(baos, ProxyStream.ERR))
    val promptLogger = new MultiLinePromptLogger(
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
      promptLogger: MultiLinePromptLogger,
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
          "======================================================================================================================",
          ""
        )
      }
    }

    test("interactive") {
      if (!Util.windowsPlatform) {
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

        // Adding new ticker entries doesn't appear immediately,
        // Only after some time has passed do we start displaying the new ticker entry,
        // to ensure it is meaningful to read and not just something that will flash and disappear
        val newTaskThread = new Thread(() => {
          val newPrefixLogger = new PrefixLogger(promptLogger, "[2]")
          newPrefixLogger.ticker("[2]", "[2/456]", "my-task-new")
          newPrefixLogger.errorStream.println("I AM COW")
          newPrefixLogger.errorStream.println("HEAR ME MOO")
        })
        newTaskThread.start()
        newTaskThread.join()

        // For short-lived ticker entries that are removed quickly, they never
        // appear in the prompt at all even though they can run and generate logs
        val shortLivedSemaphore = new Object()
        val shortLivedThread = new Thread(() => {
          val newPrefixLogger = new PrefixLogger(promptLogger, "[3]")
          newPrefixLogger.ticker("[3]", "[3/456]", "my-task-short-lived")
          newPrefixLogger.errorStream.println("hello short lived")
          shortLivedSemaphore.synchronized(shortLivedSemaphore.notify())

          newPrefixLogger.errorStream.println("goodbye short lived")

          shortLivedSemaphore.synchronized(shortLivedSemaphore.wait())
          newPrefixLogger.endTicker()
        })
        shortLivedThread.start()
        shortLivedSemaphore.synchronized(shortLivedSemaphore.wait())

        // my-task-new does not appear yet because it is too new
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "[1/456] my-task",
          "[1] HELLO",
          "[1] WORLD",
          "[2/456] my-task-new",
          "[2] I AM COW",
          "[2] HEAR ME MOO",
          "[3/456] my-task-short-lived",
          "[3] hello short lived",
          "[3] goodbye short lived",
          "  123/456 ============================ TITLE ============================== 10s",
          "[1] my-task 10s"
        )

        shortLivedSemaphore.synchronized(shortLivedSemaphore.notify())
        shortLivedThread.join()

        now += 1000

        // my-task-new appears by now, but my-task-short-lived has already ended and never appears
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "[1/456] my-task",
          "[1] HELLO",
          "[1] WORLD",
          "[2/456] my-task-new",
          "[2] I AM COW",
          "[2] HEAR ME MOO",
          "[3/456] my-task-short-lived",
          "[3] hello short lived",
          "[3] goodbye short lived",
          "  123/456 ============================ TITLE ============================== 11s",
          "[1] my-task 11s",
          "[2] my-task-new 1s"
        )

        promptLogger.endTicker()

        now += 10

        // Even after ending my-task, it remains on the ticker for a moment before being removed
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "[1/456] my-task",
          "[1] HELLO",
          "[1] WORLD",
          "[2/456] my-task-new",
          "[2] I AM COW",
          "[2] HEAR ME MOO",
          "[3/456] my-task-short-lived",
          "[3] hello short lived",
          "[3] goodbye short lived",
          "  123/456 ============================ TITLE ============================== 11s",
          "[1] my-task 11s",
          "[2] my-task-new 1s"
        )

        now += 1000

        // When my-task disappears from the ticker, it leaves a blank line for a
        // moment to preserve the height of the prompt
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "[1/456] my-task",
          "[1] HELLO",
          "[1] WORLD",
          "[2/456] my-task-new",
          "[2] I AM COW",
          "[2] HEAR ME MOO",
          "[3/456] my-task-short-lived",
          "[3] hello short lived",
          "[3] goodbye short lived",
          "  123/456 ============================ TITLE ============================== 12s",
          "[2] my-task-new 2s",
          ""
        )

        now += 10000

        // Only after more time does the prompt shrink back
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "[1/456] my-task",
          "[1] HELLO",
          "[1] WORLD",
          "[2/456] my-task-new",
          "[2] I AM COW",
          "[2] HEAR ME MOO",
          "[3/456] my-task-short-lived",
          "[3] hello short lived",
          "[3] goodbye short lived",
          "  123/456 ============================ TITLE ============================== 22s",
          "[2] my-task-new 12s"
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
          "[3/456] my-task-short-lived",
          "[3] hello short lived",
          "[3] goodbye short lived",
          "123/456 ============================== TITLE ============================== 32s",
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

        promptLogger.globalTicker("123/456")
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "  123/456 ============================ TITLE ================================= "
        )
        promptLogger.ticker("[1]", "[1/456]", "my-task")

        now += 100

        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "  123/456 ============================ TITLE ================================= "
        )

        promptLogger.endTicker()

        val newTaskThread = new Thread(() => {
          promptLogger.ticker("[2]", "[2/456]", "my-task-new")
          now += 100
          promptLogger.endTicker()
        })
        newTaskThread.start()
        newTaskThread.join()

        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "  123/456 ============================ TITLE ================================= "
        )

        val newTaskThread2 = new Thread(() => {
          promptLogger.ticker("[2]", "[2/456]", "my-task-new")
          now += 100
        })
        newTaskThread2.start()
        newTaskThread2.join()
        promptLogger.refreshPrompt()
        check(promptLogger, baos)(
          "  123/456 ============================ TITLE ================================= ",
          "[2] my-task-new "
        )
      }
    }
  }
}
