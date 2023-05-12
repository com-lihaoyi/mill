package mill.integration

import utest._
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

  val maxDuration = 30000
  val tests = Tests {
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

    def testWatchSource(show: Boolean) = {
      val showArgs = if (show) Seq("show") else Nil

      val evalResult = Future { evalTimeoutStdout(maxDuration, "--watch", showArgs, "qux") }
      val expectedPrints = collection.mutable.Buffer.empty[String]
      val expectedShows = collection.mutable.Buffer.empty[String]

      awaitCompletionMarker("initialized0")
      awaitCompletionMarker("quxRan0")
      expectedPrints.append(
        "Setting up build.sc",
        "Running qux foo contents initial-foo1 initial-foo2",
        "Running qux bar contents initial-bar"
      )
      expectedShows.append(
        "Running qux foo contents initial-foo1 initial-foo2 Running qux bar contents initial-bar"
      )

      os.write.over(wsRoot / "foo1.txt", "edited-foo1")
      awaitCompletionMarker("quxRan1")
      expectedPrints.append(
        "Running qux foo contents edited-foo1 initial-foo2",
        "Running qux bar contents initial-bar"
      )
      expectedShows.append(
        "Running qux foo contents edited-foo1 initial-foo2 Running qux bar contents initial-bar"
      )

      os.write.over(wsRoot / "foo2.txt", "edited-foo2")
      awaitCompletionMarker("quxRan2")
      expectedPrints.append(
        "Running qux foo contents edited-foo1 edited-foo2",
        "Running qux bar contents initial-bar"
      )
      expectedShows.append(
        "Running qux foo contents edited-foo1 edited-foo2 Running qux bar contents initial-bar"
      )

      os.write.over(wsRoot / "bar.txt", "edited-bar")
      awaitCompletionMarker("quxRan3")
      expectedPrints.append(
        "Running qux foo contents edited-foo1 edited-foo2",
        "Running qux bar contents edited-bar"
      )
      expectedShows.append(
        "Running qux foo contents edited-foo1 edited-foo2 Running qux bar contents edited-bar"
      )

      os.write.append(wsRoot / "build.sc", "\ndef unrelated = true")
      awaitCompletionMarker("initialized1")
      expectedPrints.append(
        "Setting up build.sc",
        "Running qux foo contents edited-foo1 edited-foo2",
        "Running qux bar contents edited-bar"
      )
      expectedShows.append(
        "Running qux foo contents edited-foo1 edited-foo2 Running qux bar contents edited-bar"
      )

      os.write.over(wsRoot / "watchValue.txt", "exit")
      awaitCompletionMarker("initialized2")
      expectedPrints.append("Setting up build.sc")

      val res = Await.result(evalResult, Duration.apply(maxDuration, SECONDS))

      val (shows, prints) = res.out.linesIterator.toVector.partition(_.startsWith("\""))

      assert(prints == expectedPrints)
      if (show) assert(shows == expectedShows.map('"' + _ + '"'))
    }

    test("sources") {

      test("noshow") - retry(3) { testWatchSource(false) }
      test("show") - retry(3) { testWatchSource(true) }
    }

    def testWatchInput(show: Boolean) = {
      val showArgs = if (show) Seq("show") else Nil

      val evalResult = Future { evalTimeoutStdout(maxDuration, "--watch", showArgs, "lol") }
      val expectedPrints = collection.mutable.Buffer.empty[String]
      val expectedShows = collection.mutable.Buffer.empty[String]

      awaitCompletionMarker("initialized0")
      awaitCompletionMarker("lolRan0")
      expectedPrints.append(
        "Setting up build.sc",
        "Running lol baz contents initial-baz"
      )
      expectedShows.append("Running lol baz contents initial-baz")

      os.write.over(wsRoot / "baz.txt", "edited-baz")
      awaitCompletionMarker("lolRan1")
      expectedPrints.append("Running lol baz contents edited-baz")
      expectedShows.append("Running lol baz contents edited-baz")

      os.write.over(wsRoot / "watchValue.txt", "edited-watchValue")
      awaitCompletionMarker("initialized1")
      expectedPrints.append("Setting up build.sc")
      expectedShows.append("Running lol baz contents edited-baz")

      os.write.over(wsRoot / "watchValue.txt", "exit")
      awaitCompletionMarker("initialized2")
      expectedPrints.append("Setting up build.sc")

      val res = Await.result(evalResult, Duration.apply(maxDuration, SECONDS))

      val (shows, prints) = res.out.linesIterator.toVector.partition(_.startsWith("\""))
      assert(prints == expectedPrints)
      if (show) assert(shows == expectedShows.map('"' + _ + '"'))
    }

    test("input") {

      test("noshow") - retry(3) { testWatchInput(false) }
      test("show") - retry(3) { testWatchInput(true) }
    }
  }
}
