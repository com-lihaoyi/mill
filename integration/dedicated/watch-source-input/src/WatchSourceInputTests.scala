package mill.integration

import mill.constants.Util
import mill.testkit.{UtestIntegrationTestSuite, IntegrationTester}
import utest.*

/**
 * Test to make sure that `--watch` works in the following cases:
 *
 * 1. `Task.Source`
 * 2. `Task.Sources`
 * 3. `Task.Input`
 * 4. `mill.api.BuildCtx.watchValue`
 * 5. Implicitly watched files, like `build.mill`
 */
trait WatchTests extends UtestIntegrationTestSuite {

  val maxDurationMillis: Int = if (sys.env.contains("CI")) 120000 else 15000

  def watchUnavailable(spawned: IntegrationTester.SpawnedProcess): Boolean = {
    val output = spawned.out.text() + spawned.err.text()
    output.contains("FSEventStreamStart returned false") ||
    output.contains("can't set up watch, no file system changes detected")
  }

  def stopIfWatchUnavailable(spawned: IntegrationTester.SpawnedProcess): Boolean =
    if (watchUnavailable(spawned)) {
      spawned.process.destroy(recursive = false)
      spawned.process.waitFor()
      true
    } else false

  def awaitCompletionMarker(
      tester: IntegrationTester,
      name: String,
      spawned: IntegrationTester.SpawnedProcess
  ): Boolean = {
    val maxTime = System.currentTimeMillis() + maxDurationMillis
    while (!os.exists(tester.workspacePath / "out" / name)) {
      if (stopIfWatchUnavailable(spawned)) return false
      if (System.currentTimeMillis() > maxTime) {
        sys.error(s"awaitCompletionMarker($name) timed out")
      }
      Thread.sleep(100)
    }
    true
  }

  def testBase(
      spawned: IntegrationTester.SpawnedProcess
  )(f: IntegrationTester.SpawnedProcess => Unit): Unit = {
    f(spawned)
    if (spawned.process.isAlive()) spawned.process.waitFor()
  }

  def assertLines(
      spawned: IntegrationTester.SpawnedProcess,
      show: Boolean,
      expectedOut: Seq[String],
      expectedErr: Seq[String],
      expectedShows: Seq[String]
  ): Unit = {
    // Poll until the expected output arrives or the timeout is reached.
    // This is necessary because when using `show`, the return value is printed
    // to stdout *after* the task body finishes (and after the completion marker
    // is written), so there is a race between awaitCompletionMarker returning
    // and the show output actually being buffered by the test process.
    val deadline = System.currentTimeMillis() + maxDurationMillis

    def currentState() = {
      val outLines = spawned.out.lines()
      val errLines = spawned.err.lines()
      val (shows, out) =
        if (show) outLines.partition(_.startsWith("\""))
        else (Vector.empty[String], outLines)
      val err = errLines.filter(s => s.startsWith("Setting up ") || s.startsWith("Running "))
      (shows, out, err)
    }

    def isDone(shows: Vector[String], out: Vector[String], err: Vector[String]): Boolean = {
      val expectedShowsMapped = expectedShows.map('"' + _ + '"')
      if (show)
        shows == expectedShowsMapped && out == expectedOut && err == expectedErr
      else
        out == expectedOut ++ expectedErr
    }

    var state = currentState()
    while (!isDone(state._1, state._2, state._3) && System.currentTimeMillis() < deadline) {
      Thread.sleep(10)
      state = currentState()
    }

    val (shows, out, err) = state

    if (show) {
      assert(out == expectedOut)
      assert(err == expectedErr)
      assert(shows == expectedShows.map('"' + _ + '"'))
    } else {
      assert(out == expectedOut ++ expectedErr)
      assert(err.isEmpty)
    }

    spawned.clear()
  }
}

object WatchSourceTests extends WatchTests {
  val tests: Tests = Tests {
    def testWatchSource(tester: IntegrationTester, show: Boolean): Unit = {
      import tester.*
      val showArgs = if (show) Seq("show") else Nil
      val spawned = spawn(("--watch", showArgs, "qux"))

      testBase(spawned) { spawned =>
        if (!awaitCompletionMarker(tester, "initialized0", spawned)) return
        if (!awaitCompletionMarker(tester, "quxRan0", spawned)) return
        assertLines(
          spawned,
          show,
          expectedOut = Seq("Setting up build.mill"),
          expectedErr = Seq(
            "Running qux foo contents initial-foo1 initial-foo2",
            "Running qux bar contents initial-bar"
          ),
          expectedShows = Seq(
            "Running qux foo contents initial-foo1 initial-foo2 Running qux bar contents initial-bar"
          )
        )

        os.write.over(workspacePath / "foo1.txt", "edited-foo1")
        if (!awaitCompletionMarker(tester, "quxRan1", spawned)) return
        assertLines(
          spawned,
          show,
          expectedOut = Seq(),
          expectedErr = Seq(
            "Running qux foo contents edited-foo1 initial-foo2",
            "Running qux bar contents initial-bar"
          ),
          expectedShows = Seq(
            "Running qux foo contents edited-foo1 initial-foo2 Running qux bar contents initial-bar"
          )
        )

        os.write.over(workspacePath / "foo2.txt", "edited-foo2")
        if (!awaitCompletionMarker(tester, "quxRan2", spawned)) return
        assertLines(
          spawned,
          show,
          expectedOut = Seq(),
          expectedErr = Seq(
            "Running qux foo contents edited-foo1 edited-foo2",
            "Running qux bar contents initial-bar"
          ),
          expectedShows = Seq(
            "Running qux foo contents edited-foo1 edited-foo2 Running qux bar contents initial-bar"
          )
        )

        os.write.over(workspacePath / "bar.txt", "edited-bar")
        if (!awaitCompletionMarker(tester, "quxRan3", spawned)) return
        assertLines(
          spawned,
          show,
          expectedOut = Seq(),
          expectedErr = Seq(
            "Running qux foo contents edited-foo1 edited-foo2",
            "Running qux bar contents edited-bar"
          ),
          expectedShows = Seq(
            "Running qux foo contents edited-foo1 edited-foo2 Running qux bar contents edited-bar"
          )
        )

        os.write.append(workspacePath / "build.mill", "\ndef unrelated = true")
        if (!awaitCompletionMarker(tester, "initialized1", spawned)) return
        assertLines(
          spawned,
          show,
          expectedOut = Seq("Setting up build.mill"),
          expectedErr = Seq(),
          expectedShows = Seq()
        )

        os.write.over(workspacePath / "watchValue.txt", "exit")
        if (!awaitCompletionMarker(tester, "initialized2", spawned)) return
        assertLines(
          spawned,
          show,
          expectedOut =
            if (show) Seq("{}", "Setting up build.mill") else Seq("Setting up build.mill"),
          expectedErr = Seq(),
          expectedShows = Seq()
        )
      }
    }

    test("sources") {

      // Make sure we clean up the workspace between retries
      test("noshow") - retry(2) {
        integrationTest { tester =>
          if (!Util.isWindows) testWatchSource(tester, false)
        }
      }
      test("show") - retry(2) {
        integrationTest { tester =>
          if (!Util.isWindows) testWatchSource(tester, true)
        }
      }
    }
  }
}

object WatchInputTests extends WatchTests {
  val tests: Tests = Tests {

    def testWatchInput(tester: IntegrationTester, show: Boolean): Unit = {
      val showArgs = if (show) Seq("show") else Nil
      import tester.*
      val spawned = spawn(("--watch", showArgs, "lol"))

      testBase(spawned) { spawned =>
        if (!awaitCompletionMarker(tester, "initialized0", spawned)) return
        if (!awaitCompletionMarker(tester, "lolRan0", spawned)) return
        assertLines(
          spawned,
          show,
          expectedOut = Seq("Setting up build.mill"),
          expectedErr = Seq("Running lol baz contents initial-baz"),
          expectedShows = Seq("Running lol baz contents initial-baz")
        )

        os.write.over(workspacePath / "baz.txt", "edited-baz")
        if (!awaitCompletionMarker(tester, "lolRan1", spawned)) return
        assertLines(
          spawned,
          show,
          expectedOut = Seq(),
          expectedErr = Seq("Running lol baz contents edited-baz"),
          expectedShows = Seq("Running lol baz contents edited-baz")
        )

        os.write.over(workspacePath / "watchValue.txt", "edited-watchValue")
        if (!awaitCompletionMarker(tester, "initialized1", spawned)) return
        assertLines(
          spawned,
          show,
          expectedOut = Seq("Setting up build.mill"),
          expectedErr = Seq(),
          expectedShows = Seq()
        )

        os.write.over(workspacePath / "watchValue.txt", "exit")
        if (!awaitCompletionMarker(tester, "initialized2", spawned)) return
        assertLines(
          spawned,
          show,
          expectedOut =
            if (show) Seq("{}", "Setting up build.mill") else Seq("Setting up build.mill"),
          expectedErr = Seq(),
          expectedShows = Seq()
        )
      }
    }

    test("input") {

      // Make sure we clean up the workspace between retries
      test("noshow") - retry(2) {
        integrationTest { tester =>
          if (!Util.isWindows) testWatchInput(tester, false)
        }
      }
      test("show") - retry(2) {
        integrationTest { tester =>
          if (!Util.isWindows) testWatchInput(tester, true)
        }
      }
    }
  }
}
