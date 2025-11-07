package mill.integration

import mill.constants.Util
import mill.testkit.{UtestIntegrationTestSuite, IntegrationTester}
import utest._

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

  def awaitCompletionMarker(tester: IntegrationTester, name: String): Unit = {
    val maxTime = System.currentTimeMillis() + maxDurationMillis
    while (!os.exists(tester.workspacePath / "out" / name)) {
      if (System.currentTimeMillis() > maxTime) {
        sys.error(s"awaitCompletionMarker($name) timed out")
      }
      Thread.sleep(100)
    }
  }

  def testBase(
      spawned: IntegrationTester.SpawnedProcess
  )(f: IntegrationTester.SpawnedProcess => Unit): Unit = {
    f(spawned)
    spawned.process.waitFor()
  }

  def assertLines(
      spawned: IntegrationTester.SpawnedProcess,
      show: Boolean,
      expectedOut: Seq[String],
      expectedErr: Seq[String],
      expectedShows: Seq[String]
  ): Unit = {
    val outLines = spawned.out.lines()
    val errLines = spawned.err.lines()

    val (shows, out) =
      if (show) outLines.partition(_.startsWith("\""))
      else (Vector.empty[String], outLines)

    val err = errLines.filter(s => s.startsWith("Setting up ") || s.startsWith("Running "))

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
        awaitCompletionMarker(tester, "initialized0")
        awaitCompletionMarker(tester, "quxRan0")
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
        awaitCompletionMarker(tester, "quxRan1")
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
        awaitCompletionMarker(tester, "quxRan2")
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
        awaitCompletionMarker(tester, "quxRan3")
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
        awaitCompletionMarker(tester, "initialized1")
        assertLines(
          spawned,
          show,
          expectedOut = Seq("Setting up build.mill"),
          expectedErr = Seq(),
          expectedShows = Seq()
        )

        os.write.over(workspacePath / "watchValue.txt", "exit")
        awaitCompletionMarker(tester, "initialized2")
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
      test("noshow") - retry(1) {
        integrationTest { tester =>
          if (!Util.isWindows) testWatchSource(tester, false)
        }
      }
      test("show") - retry(1) {
        integrationTest { tester =>
          if (!Util.isWindows) testWatchSource(tester, true)
        }
      }
    }
  }
}

object WatchInputTests extends WatchTests {
  val tests: Tests = Tests {

    def testWatchInput(tester: IntegrationTester, show: Boolean) = {
      val showArgs = if (show) Seq("show") else Nil
      import tester.*
      val spawned = spawn(("--watch", showArgs, "lol"))

      testBase(spawned) { spawned =>
        awaitCompletionMarker(tester, "initialized0")
        awaitCompletionMarker(tester, "lolRan0")
        assertLines(
          spawned,
          show,
          expectedOut = Seq("Setting up build.mill"),
          expectedErr = Seq("Running lol baz contents initial-baz"),
          expectedShows = Seq("Running lol baz contents initial-baz")
        )

        os.write.over(workspacePath / "baz.txt", "edited-baz")
        awaitCompletionMarker(tester, "lolRan1")
        assertLines(
          spawned,
          show,
          expectedOut = Seq(),
          expectedErr = Seq("Running lol baz contents edited-baz"),
          expectedShows = Seq("Running lol baz contents edited-baz")
        )

        os.write.over(workspacePath / "watchValue.txt", "edited-watchValue")
        awaitCompletionMarker(tester, "initialized1")
        assertLines(
          spawned,
          show,
          expectedOut = Seq("Setting up build.mill"),
          expectedErr = Seq(),
          expectedShows = Seq()
        )

        os.write.over(workspacePath / "watchValue.txt", "exit")
        awaitCompletionMarker(tester, "initialized2")
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
      test("noshow") - retry(1) {
        integrationTest { tester =>
          if (!Util.isWindows) testWatchInput(tester, false)
        }
      }
      test("show") - retry(1) {
        integrationTest { tester =>
          if (!Util.isWindows) testWatchInput(tester, true)
        }
      }
    }
  }
}
