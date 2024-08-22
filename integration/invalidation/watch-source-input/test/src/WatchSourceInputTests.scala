package mill.integration

import mill.main.client.Util
import utest._

import scala.collection.mutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.concurrent.duration.SECONDS
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Test to make sure that `--watch` works in the following cases:
 *
 * 1. `T.source`
 * 2. `T.sources`
 * 3. `T.input`
 * 4. `interp.watchValue`
 * 5. Implicitly watched files, like `build.sc`
 */
object WatchSourceInputTests extends IntegrationTestSuite {

  val maxDuration = 60000
  val tests: Tests = Tests {
    val wsRoot = initWorkspace()

    def awaitCompletionMarker(name: String) = {
      val maxTime = System.currentTimeMillis() + maxDuration
      while (!os.exists(wsRoot / "out" / name)) {
        if (System.currentTimeMillis() > maxTime) {
          sys.error(s"awaitCompletionMarker($name) timed out")
        }
        Thread.sleep(100)
      }
    }

    def testBase(show: Boolean)(f: (
        mutable.Buffer[String],
        mutable.Buffer[String],
        mutable.Buffer[String]
    ) => IntegrationTestSuite.EvalResult): Unit = {
      val expectedOut = mutable.Buffer.empty[String]
      // Most of these are normal `println`s, so they go to `stdout` by
      // default unless you use `show` in which case they go to `stderr`.
      val expectedErr = if (show) mutable.Buffer.empty[String] else expectedOut
      val expectedShows = mutable.Buffer.empty[String]
      val res = f(expectedOut, expectedErr, expectedShows)
      val (shows, out) = res.out.linesIterator.toVector.partition(_.startsWith("\""))
      val err = res.err.linesIterator.toVector
        .filter(!_.contains("Compiling compiler interface..."))
        .filter(!_.contains("Watching for changes"))
        .filter(!_.contains("[info] compiling"))
        .filter(!_.contains("[info] done compiling"))

      assert(out == expectedOut)

      // If show is not enabled, we don't expect any of our custom prints to go to stderr
      if (show) assert(err == expectedErr)
      else assert(err.isEmpty)

      if (show) assert(shows == expectedShows.map('"' + _ + '"'))
    }

    def testWatchSource(show: Boolean) =
      testBase(show) { (expectedOut, expectedErr, expectedShows) =>
        val showArgs = if (show) Seq("show") else Nil

        val evalResult = Future { evalTimeoutStdout(maxDuration, "--watch", showArgs, "qux") }

        awaitCompletionMarker("initialized0")
        awaitCompletionMarker("quxRan0")
        expectedOut.append(
          "Setting up build.sc"
        )
        expectedErr.append(
          "Running qux foo contents initial-foo1 initial-foo2",
          "Running qux bar contents initial-bar"
        )
        expectedShows.append(
          "Running qux foo contents initial-foo1 initial-foo2 Running qux bar contents initial-bar"
        )

        os.write.over(wsRoot / "foo1.txt", "edited-foo1")
        awaitCompletionMarker("quxRan1")
        expectedErr.append(
          "Running qux foo contents edited-foo1 initial-foo2",
          "Running qux bar contents initial-bar"
        )
        expectedShows.append(
          "Running qux foo contents edited-foo1 initial-foo2 Running qux bar contents initial-bar"
        )

        os.write.over(wsRoot / "foo2.txt", "edited-foo2")
        awaitCompletionMarker("quxRan2")
        expectedErr.append(
          "Running qux foo contents edited-foo1 edited-foo2",
          "Running qux bar contents initial-bar"
        )
        expectedShows.append(
          "Running qux foo contents edited-foo1 edited-foo2 Running qux bar contents initial-bar"
        )

        os.write.over(wsRoot / "bar.txt", "edited-bar")
        awaitCompletionMarker("quxRan3")
        expectedErr.append(
          "Running qux foo contents edited-foo1 edited-foo2",
          "Running qux bar contents edited-bar"
        )
        expectedShows.append(
          "Running qux foo contents edited-foo1 edited-foo2 Running qux bar contents edited-bar"
        )

        os.write.append(wsRoot / "build.sc", "\ndef unrelated = true")
        awaitCompletionMarker("initialized1")
        expectedOut.append(
          "Setting up build.sc"
          // These targets do not re-evaluate, because the change to the build
          // file was unrelated to them and does not affect their transitive callgraph
          //        "Running qux foo contents edited-foo1 edited-foo2",
          //        "Running qux bar contents edited-bar"
        )
        expectedShows.append(
          "Running qux foo contents edited-foo1 edited-foo2 Running qux bar contents edited-bar"
        )

        os.write.over(wsRoot / "watchValue.txt", "exit")
        awaitCompletionMarker("initialized2")
        expectedOut.append("Setting up build.sc")

        Await.result(evalResult, Duration.apply(maxDuration, SECONDS))
      }

    test("sources") {

      // Make sure we clean up the workspace between retries
      test("noshow") - retry(3) { if (!Util.isWindows) { initWorkspace(); testWatchSource(false) } }
      test("show") - retry(3) { if (!Util.isWindows) { initWorkspace(); testWatchSource(true) } }
    }

    def testWatchInput(show: Boolean) =
      testBase(show) { (expectedOut, expectedErr, expectedShows) =>
        val showArgs = if (show) Seq("show") else Nil

        val evalResult = Future { evalTimeoutStdout(maxDuration, "--watch", showArgs, "lol") }

        awaitCompletionMarker("initialized0")
        awaitCompletionMarker("lolRan0")
        expectedOut.append(
          "Setting up build.sc"
        )
        expectedErr.append(
          "Running lol baz contents initial-baz"
        )
        expectedShows.append("Running lol baz contents initial-baz")

        os.write.over(wsRoot / "baz.txt", "edited-baz")
        awaitCompletionMarker("lolRan1")
        expectedErr.append("Running lol baz contents edited-baz")
        expectedShows.append("Running lol baz contents edited-baz")

        os.write.over(wsRoot / "watchValue.txt", "edited-watchValue")
        awaitCompletionMarker("initialized1")
        expectedOut.append("Setting up build.sc")
        expectedShows.append("Running lol baz contents edited-baz")

        os.write.over(wsRoot / "watchValue.txt", "exit")
        awaitCompletionMarker("initialized2")
        expectedOut.append("Setting up build.sc")

        Await.result(evalResult, Duration.apply(maxDuration, SECONDS))
      }

    test("input") {

      // Make sure we clean up the workspace between retries
      test("noshow") - retry(3) { if (!Util.isWindows) { initWorkspace(); testWatchInput(false) } }
      test("show") - retry(3) { if (!Util.isWindows) { initWorkspace(); testWatchInput(true) } }
    }
  }
}
