package mill.integration

import mill.constants.DaemonFiles
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*
import utest.asserts.{RetryInterval, RetryMax}

import scala.concurrent.duration.DurationInt

object ConcurrencyTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(60000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)

  private def waitFile(tester: IntegrationTester.Impl, name: String) = tester.workspacePath / name

  /**
   * Block until the running task body has touched its `<name>-entered`
   * marker file. File existence is atomic from the test runner's
   * perspective — no buffered-stdout race like polling launcher output.
   */
  private def awaitEntered(tester: IntegrationTester.Impl, name: String): Unit =
    assertEventually(os.exists(tester.workspacePath / s"$name-entered"))

  private def blockedBy(
      launcher: mill.testkit.IntegrationTester.SpawnedProcess,
      command: String,
      pid: Long,
      taskName: String
  ): Boolean =
    launcher.containsLines(blockedLine(command, pid, taskName, "write")) ||
      launcher.containsLines(blockedLine(command, pid, taskName, "read"))

  private def blockedLine(command: String, pid: Long, taskName: String, kind: String): String =
    s"blocked on $kind lock '$taskName' PID $pid '$command'"

  /**
   * Exact set of "blocked on ... lock ..." lines this launcher emitted.
   * One entry per distinct line, deduped — order is not significant because
   * waits in different threads can interleave.
   */
  private def contentionMessages(
      launcher: mill.testkit.IntegrationTester.SpawnedProcess
  ): Set[String] =
    launcher.err.text().linesIterator
      .filter(_.startsWith("blocked on "))
      .toSet

  /**
   * Assert the exact set of contention waits this launcher hit. Both
   * positive (each expected wait happened) and negative (no other wait
   * happened) — so any spurious extra contention (e.g. an unintended
   * escalation to a meta-build Write that should have stayed on Read) fails
   * the test, and any missing expected contention fails it too.
   */
  private def assertContention(
      launcher: mill.testkit.IntegrationTester.SpawnedProcess,
      expected: Set[String]
  ): Unit = {
    val actual = contentionMessages(launcher)
    val missing = expected -- actual
    val extra = actual -- expected
    if (missing.nonEmpty || extra.nonEmpty) {
      throw new java.lang.AssertionError(
        s"\ncontention mismatch:\nmissing:\n  ${missing.toSeq.sorted.mkString("\n  ")}\nextra:\n  ${extra.toSeq.sorted.mkString("\n  ")}\n"
      )
    }
  }

  private def activeLauncherPid(tester: IntegrationTester.Impl, command: String): Option[Long] = {
    val millRun =
      tester.workspacePath / "out" / os.RelPath(DaemonFiles.millRun)
    if (!os.exists(millRun)) None
    else {
      os.list(millRun).iterator
        .filter(os.isFile(_))
        .flatMap { path =>
          val json = ujson.read(os.read(path)).obj
          val fileCommand = json.get("command").map(_.str)
          val filePid = json.get("pid").map(_.num.toLong)
          Option.when(fileCommand.contains(command))(filePid).flatten
        }
        .toSeq
        .lastOption
    }
  }

  private def awaitActiveLauncherPid(tester: IntegrationTester.Impl, command: String): Long = {
    var pid = Option.empty[Long]
    assertEventually {
      pid = activeLauncherPid(tester, command)
      pid.nonEmpty
    }
    pid.get
  }

  private def release(waitFile: os.Path): Unit =
    if (os.exists(waitFile)) os.remove(waitFile)

  val tests: Tests = Tests {
    test("same-task-write-lock-blocks-second-launcher") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      val gate = waitFile(tester, "same-task-wait")
      os.write.over(gate, "")

      val launcher1 = spawn(("runSameTask"))
      awaitEntered(tester, "same-task")
      val blockerPid = awaitActiveLauncherPid(tester, "runSameTask")

      val launcher2 = spawn(("runSameTask"))
      assertEventually(blockedBy(launcher2, "runSameTask", blockerPid, "sameTask"))
      assert(launcher2.process.isAlive())
      assert(!launcher2.containsLines("same-task-value"))

      release(gate)
      launcher1.process.waitFor()
      launcher2.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      assert(launcher2.process.exitCode() == 0)
      launcher1.assertContainsLines("same-task-value")
      launcher2.assertContainsLines("same-task-value")

      assertContention(launcher1, Set.empty)
      // Only `sameTask` is deterministically held while launcher 1 is
      // parked; the outer `runSameTask` Command lock often races free
      // before launcher 2 escalates.
      assertContention(launcher2, Set(blockedLine("runSameTask", blockerPid, "sameTask", "read")))
    }

    test("downstream-holds-upstream-write-lock-while-computing") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      val gate = waitFile(tester, "shared-wait")
      os.write.over(gate, "")

      val launcher1 = spawn(("runLeft"))
      awaitEntered(tester, "shared")
      val blockerPid = awaitActiveLauncherPid(tester, "runLeft")

      val launcher2 = spawn(("runShared"))
      assertEventually(blockedBy(launcher2, "runLeft", blockerPid, "shared"))
      assert(launcher2.process.isAlive())
      assert(!launcher2.containsLines("shared-value"))

      release(gate)
      launcher1.process.waitFor()
      launcher2.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      assert(launcher2.process.exitCode() == 0)
      launcher1.assertContainsLines("shared-value-left")
      launcher2.assertContainsLines("shared-value")

      assertContention(launcher1, Set.empty)
      assertContention(launcher2, Set(blockedLine("runLeft", blockerPid, "shared", "read")))
    }

    test("different-downstreams-can-share-upstream-read-lock") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      eval(("runShared"), check = true)

      val leftGate = waitFile(tester, "left-wait")
      val rightGate = waitFile(tester, "right-wait")
      os.write.over(leftGate, "")
      os.write.over(rightGate, "")

      val launcher1 = spawn(("runLeft"))
      awaitEntered(tester, "left")
      val blockerPid = awaitActiveLauncherPid(tester, "runLeft")

      val launcher2 = spawn(("runRight"))
      awaitEntered(tester, "right")
      assert(!blockedBy(launcher2, "runLeft", blockerPid, "shared"))

      release(rightGate)
      launcher2.process.waitFor()

      assert(launcher1.process.isAlive())
      assert(launcher2.process.exitCode() == 0)
      launcher2.assertContainsLines("shared-value-right")
      assert(!blockedBy(launcher2, "runLeft", blockerPid, "shared"))

      release(leftGate)
      launcher1.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      launcher1.assertContainsLines("shared-value-left")
      assert(!blockedBy(launcher1, "runRight", blockerPid, "shared"))

      // Disjoint downstreams over a cached `shared` Read: zero contention.
      assertContention(launcher1, Set.empty)
      assertContention(launcher2, Set.empty)
    }

    test("meta-build-write-update-blocked-by-active-meta-build-read") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      val gate = waitFile(tester, "meta-build-read-wait")
      os.write.over(gate, "")

      val launcher1 = spawn(("runHoldMetaBuildRead"))
      awaitEntered(tester, "meta-build-read")
      val blockerPid = awaitActiveLauncherPid(tester, "runHoldMetaBuildRead")

      modifyFile(
        workspacePath / "build.mill",
        _.replace("val fastSuffix = 0", "val fastSuffix = 1")
      )

      val launcher2 = spawn(("runShared"))
      assertEventually(blockedBy(
        launcher2,
        "runHoldMetaBuildRead",
        blockerPid,
        "mill-build/build.mill"
      ))
      assert(launcher2.process.isAlive())
      assert(!launcher2.containsLines("shared-value"))

      release(gate)
      launcher1.process.waitFor()
      launcher2.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      assert(launcher2.process.exitCode() == 0)
      launcher1.assertContainsLines("fast-value-0")
      launcher2.assertContainsLines("shared-value")

      assertContention(launcher1, Set.empty)
      assertContention(
        launcher2,
        Set(blockedLine("runHoldMetaBuildRead", blockerPid, "mill-build/build.mill", "write"))
      )
    }

    test("concurrent-launchers-do-not-block-on-meta-build-when-not-rebuilding") -
      integrationTest { tester =>
        import tester.*
        assert(tester.daemonMode)

        // Warm meta-build classloader so subsequent launchers find it
        // reusable and only need Read leases.
        eval(("runFast"), check = true)

        val metaGate = waitFile(tester, "meta-build-read-wait")
        os.write.over(metaGate, "")

        // Launcher 1: enters final-tasks phase, retains meta-build Read
        // leases at every depth, then parks at the metaGate.
        val launcher1 = spawn(("runHoldMetaBuildRead"))
        awaitEntered(tester, "meta-build-read")

        // Build unchanged — launcher 2 should coexist on Read leases
        // with launcher 1's retained Reads, no Write escalation.
        val launcher2 = spawn(("runFast"))
        assertEventually(!launcher2.process.isAlive())

        assert(launcher2.process.exitCode() == 0)
        launcher2.assertContainsLines("fast-value-0")

        release(metaGate)
        launcher1.process.waitFor()

        assert(launcher1.process.exitCode() == 0)
        launcher1.assertContainsLines("fast-value-0")

        // Zero contention expected: any wait — meta-build or per-task — fails.
        assertContention(launcher1, Set.empty)
        assertContention(launcher2, Set.empty)
      }

    test("exclusive-task-blocks-everything") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      // Warm meta-build classloader so both launchers see it as reusable —
      // isolates the assertion to the `exclusive` lock contention rather
      // than mixing in a meta-build wait.
      eval(("runFast"), check = true)

      val gate = waitFile(tester, "exclusive-wait")
      os.write.over(gate, "")

      // Launcher 1 holds `exclusiveLock(Write)` and parks.
      val launcher1 = spawn(("runExclusive"))
      awaitEntered(tester, "exclusive")
      val blockerPid = awaitActiveLauncherPid(tester, "runExclusive")

      // Launcher 2's `exclusiveLock(Read)` blocks on launcher 1's Write
      // even though `runFast` is otherwise unrelated to `runExclusive`.
      val launcher2 = spawn(("runFast"))
      assertEventually(blockedBy(launcher2, "runExclusive", blockerPid, "exclusive"))
      assert(launcher2.process.isAlive())
      assert(!launcher2.containsLines("fast-value-0"))

      release(gate)
      launcher1.process.waitFor()
      launcher2.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      assert(launcher2.process.exitCode() == 0)
      launcher1.assertContainsLines("exclusive-value")
      launcher2.assertContainsLines("fast-value-0")

      assertContention(launcher1, Set.empty)
      // Same `blocked on ...` shape as per-task waits — confirms
      // exclusive-lock waits go through the same WaitReporter pipeline.
      assertContention(
        launcher2,
        Set(blockedLine("runExclusive", blockerPid, "exclusive", "read"))
      )
    }

    test("stale-shared-worker-is-not-closed-under-read-lock") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      val versionFile = workspacePath / "worker-version"
      val closeMarker = workspacePath / "worker-closed-0"
      os.write.over(versionFile, "0")
      eval(("runWorkerValue"), check = true)

      val gate = waitFile(tester, "worker-use-wait")
      os.write.over(gate, "")

      val launcher1 = spawn(("runUseWorker"))
      awaitEntered(tester, "worker-use")
      val blockerPid = awaitActiveLauncherPid(tester, "runUseWorker")

      os.write.over(versionFile, "1")
      val launcher2 = spawn(("runUseWorker"))
      // Version change forces `testWorker` recreation; launcher 1's
      // retained Read on `testWorker` blocks launcher 2's Write.
      // (`workerVersion` is Task.Input, so it bypasses the per-task lock.)
      assertEventually(blockedBy(launcher2, "runUseWorker", blockerPid, "testWorker"))
      assert(launcher2.process.isAlive())
      assert(!os.exists(closeMarker))

      release(gate)
      launcher1.process.waitFor()
      assertEventually(os.exists(closeMarker))

      release(gate)
      launcher2.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      assert(launcher2.process.exitCode() == 0)
      launcher1.assertContainsLines("worker-value-0")
      launcher2.assertContainsLines("worker-value-1")

      assertContention(launcher1, Set.empty)
      assertContention(
        launcher2,
        Set(blockedLine("runUseWorker", blockerPid, "testWorker", "write"))
      )
    }
  }
}
