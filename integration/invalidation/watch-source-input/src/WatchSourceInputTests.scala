package mill.integration

import mill.testkit.{UtestIntegrationTestSuite, IntegrationTester}

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
 * 1. `Task.Source`
 * 2. `Task.Sources`
 * 3. `Task.Input`
 * 4. `interp.watchValue`
 * 5. Implicitly watched files, like `build.mill`
 */
object WatchSourceInputTests extends UtestIntegrationTestSuite {

  val maxDuration = 60000
  val tests: Tests = Tests {
    def awaitCompletionMarker(tester: IntegrationTester, name: String) = {
      val maxTime = System.currentTimeMillis() + maxDuration
      while (!os.exists(tester.workspacePath / "out" / name)) {
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
    ) => IntegrationTester.EvalResult): Unit = {
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
        .filter(!_.contains("mill-server/ exitCode file not found"))

      assert(out == expectedOut)

      // If show is not enabled, we don't expect any of our custom prints to go to stderr
      if (show) assert(err == expectedErr)
      else assert(err.isEmpty)

      if (show) assert(shows == expectedShows.map('"' + _ + '"'))
    }

    def testWatchSource(tester: IntegrationTester, show: Boolean) =
      testBase(show) { (expectedOut, expectedErr, expectedShows) =>
        val showArgs = if (show) Seq("show") else Nil
        import tester._
        val evalResult = Future { eval(("--watch", showArgs, "qux"), timeout = maxDuration) }

        awaitCompletionMarker(tester, "initialized0")
        awaitCompletionMarker(tester, "quxRan0")
        expectedOut.append(
          "Setting up build.mill"
        )
        expectedErr.append(
          "Running qux foo contents initial-foo1 initial-foo2",
          "Running qux bar contents initial-bar"
        )
        expectedShows.append(
          "Running qux foo contents initial-foo1 initial-foo2 Running qux bar contents initial-bar"
        )

        os.write.over(workspacePath / "foo1.txt", "edited-foo1")
        awaitCompletionMarker(tester, "quxRan1")
        expectedErr.append(
          "Running qux foo contents edited-foo1 initial-foo2",
          "Running qux bar contents initial-bar"
        )
        expectedShows.append(
          "Running qux foo contents edited-foo1 initial-foo2 Running qux bar contents initial-bar"
        )

        os.write.over(workspacePath / "foo2.txt", "edited-foo2")
        awaitCompletionMarker(tester, "quxRan2")
        expectedErr.append(
          "Running qux foo contents edited-foo1 edited-foo2",
          "Running qux bar contents initial-bar"
        )
        expectedShows.append(
          "Running qux foo contents edited-foo1 edited-foo2 Running qux bar contents initial-bar"
        )

        os.write.over(workspacePath / "bar.txt", "edited-bar")
        awaitCompletionMarker(tester, "quxRan3")
        expectedErr.append(
          "Running qux foo contents edited-foo1 edited-foo2",
          "Running qux bar contents edited-bar"
        )
        expectedShows.append(
          "Running qux foo contents edited-foo1 edited-foo2 Running qux bar contents edited-bar"
        )

        os.write.append(workspacePath / "build.mill", "\ndef unrelated = true")
        awaitCompletionMarker(tester, "initialized1")
        expectedOut.append(
          "Setting up build.mill"
          // These tasks do not re-evaluate, because the change to the build
          // file was unrelated to them and does not affect their transitive callgraph
          //        "Running qux foo contents edited-foo1 edited-foo2",
          //        "Running qux bar contents edited-bar"
        )
        expectedShows.append(
          "Running qux foo contents edited-foo1 edited-foo2 Running qux bar contents edited-bar"
        )

        os.write.over(workspacePath / "watchValue.txt", "exit")
        awaitCompletionMarker(tester, "initialized2")
        expectedOut.append("Setting up build.mill")

        Await.result(evalResult, Duration.apply(maxDuration, SECONDS))
      }

    test("sources") {

      // Make sure we clean up the workspace between retries
      test("noshow") - retry(3) {
        integrationTest { tester =>
          if (!Util.isWindows) {
            testWatchSource(tester, false)
          }
        }
      }
      test("show") - retry(3) {
        integrationTest { tester =>
          if (!Util.isWindows) {
            testWatchSource(tester, true)
          }
        }
      }
    }

    def testWatchInput(tester: IntegrationTester, show: Boolean) =
      testBase(show) { (expectedOut, expectedErr, expectedShows) =>
        val showArgs = if (show) Seq("show") else Nil
        import tester._
        val evalResult = Future { eval(("--watch", showArgs, "lol"), timeout = maxDuration) }

        awaitCompletionMarker(tester, "initialized0")
        awaitCompletionMarker(tester, "lolRan0")
        expectedOut.append(
          "Setting up build.mill"
        )
        expectedErr.append(
          "Running lol baz contents initial-baz"
        )
        expectedShows.append("Running lol baz contents initial-baz")

        os.write.over(workspacePath / "baz.txt", "edited-baz")
        awaitCompletionMarker(tester, "lolRan1")
        expectedErr.append("Running lol baz contents edited-baz")
        expectedShows.append("Running lol baz contents edited-baz")

        os.write.over(workspacePath / "watchValue.txt", "edited-watchValue")
        awaitCompletionMarker(tester, "initialized1")
        expectedOut.append("Setting up build.mill")
        expectedShows.append("Running lol baz contents edited-baz")

        os.write.over(workspacePath / "watchValue.txt", "exit")
        awaitCompletionMarker(tester, "initialized2")
        expectedOut.append("Setting up build.mill")

        Await.result(evalResult, Duration.apply(maxDuration, SECONDS))
      }

    test("input") {

      // Make sure we clean up the workspace between retries
      test("noshow") - retry(3) {
        integrationTest { tester =>
          if (!Util.isWindows) {
            testWatchInput(tester, false)
          }
        }
      }
      test("show") - /*retry(3) */ {
        integrationTest { tester =>
          if (!Util.isWindows) {
            testWatchInput(tester, true)
          }
        }
      }
    }
  }
}
