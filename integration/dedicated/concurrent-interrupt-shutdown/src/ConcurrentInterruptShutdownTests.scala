package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration.*
import utest.*
import utest.asserts.*

object ConcurrentInterruptShutdownTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax((if (sys.env.contains("CI")) 120000 else 15000).millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)

  private def blockedBy(
      command: String,
      taskName: String,
      pid: Long,
      stderrText: String
  ): Boolean =
    stderrText.contains(s"blocked on read lock '$taskName' PID $pid '$command'")

  val tests: Tests = Tests {
    test("interrupt-blocked") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)
      val launcher1 = spawn(("waitForExists", "--fileName", "file1.txt"))

      assertEventually(launcher1.containsLines("Waiting on file1.txt"))
      // Two `waitForExists` invocations contend on the same per-task write lock,
      // so launcher2 blocks until launcher1 releases it.
      val launcher2 = spawn(("waitForExists", "--fileName", "file2.txt"))

      assertEventually(blockedBy(
        "waitForExists --fileName file1.txt",
        "waitForExists",
        launcher1.process.wrapped.pid(),
        launcher2.err.text()
      ))
      launcher2.process.destroy(recursive = false)
      assertEventually(!launcher2.process.isAlive())
      os.write(workspacePath / "file1.txt", "Hello world")
      assertEventually(launcher1.containsLines("Found file1.txt containing Hello world"))
    }

    test("interrupt-active") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)
      val launcher1 = spawn(("waitForExists", "--fileName", "file1.txt"))

      assertEventually(launcher1.containsLines("Waiting on file1.txt"))
      // Pre-write file2.txt so launcher2 completes immediately once it acquires
      // the lock (or after the daemon-shutdown-and-retry round-trip).
      os.write(workspacePath / "file2.txt", "Hello cow")
      val launcher2 = spawn(("waitForExists", "--fileName", "file2.txt"))

      assertEventually(blockedBy(
        "waitForExists --fileName file1.txt",
        "waitForExists",
        launcher1.process.wrapped.pid(),
        launcher2.err.text()
      ))
      launcher1.process.destroy(recursive = false)
      assertEventually(!launcher1.process.isAlive())
      assert(!(launcher1.out.text() + launcher1.err.text()).contains("Found"))
      // launcher1 was killed mid-execution which forces the daemon to shut down;
      // launcher2 should be told why (rather than crashing with "Worker wire broken")
      // and then succeed after retrying against a fresh daemon.
      assertEventually(launcher2.containsLines(
        "daemon was shut down because launcher running 'waitForExists --fileName file1.txt' was interrupted"
      ))
      assertEventually(launcher2.containsLines("Found file2.txt containing Hello cow"))
    }

    test("interrupt-active-after-stdin") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)
      val launcher1 = spawn(("waitForExists", "--fileName", "file1.txt"))
      assertEventually(launcher1.containsLines("Waiting on file1.txt"))

      val launcher2 = spawn(("readLineThenWaitForExists", "--fileName", "file2.txt"))
      launcher2.process.stdin.writeLine("Hello cow")
      launcher2.process.stdin.flush()
      assertEventually(launcher2.containsLines("Read Hello cow"))

      launcher1.process.destroy(recursive = false)
      // launcher2 already handed its stdin to the old daemon. A retry can't
      // replay that input, so launcher2 must exit instead of re-running the command.
      assertEventually(!launcher2.process.isAlive())
      assert(launcher2.err.text().contains("exiting instead of retrying"))
    }
  }
}
