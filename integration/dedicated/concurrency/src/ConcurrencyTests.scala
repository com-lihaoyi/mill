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
    launcher.containsLines(blockedLine(command, pid, taskName))

  private def blockedLine(command: String, pid: Long, taskName: String): String =
    s"Another Mill command in the current daemon is running '$command' task '$taskName' with PID $pid, waiting for it " +
      s"(tail -F out/${DaemonFiles.millConsoleTail} to see its progress)"

  /**
   * Exact set of "Another Mill command in the current daemon is running ..."
   * lines this launcher emitted. One entry per distinct line, deduped — order
   * is not significant because waits in different threads can interleave.
   */
  private def contentionMessages(
      launcher: mill.testkit.IntegrationTester.SpawnedProcess
  ): Set[String] =
    launcher.err.text().linesIterator
      .filter(_.contains("Another Mill command in the current daemon is running"))
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

      // Launcher 1 acquired every lock first; nothing to wait on.
      assertContention(launcher1, Set.empty)
      // Launcher 2 must contend on exactly the inner `sameTask` lock —
      // the only deterministically-held lock while launcher 1 is parked.
      // (The outer `runSameTask` Command lock would race: launcher 1
      // releases it as soon as its body returns, often before launcher 2
      // escalates that one. The `sameTask` Read held by the not-yet-
      // completed downstream is what makes the wait deterministic.) Any
      // other lock — notably any meta-build escalation — fails the test.
      assertContention(launcher2, Set(blockedLine("runSameTask", blockerPid, "sameTask")))
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

      // Launcher 1 acquired every lock first.
      assertContention(launcher1, Set.empty)
      // Launcher 2 must contend on exactly the per-task `shared` lock and
      // nothing else (no spurious meta-build escalation).
      assertContention(launcher2, Set(blockedLine("runLeft", blockerPid, "shared")))
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

      // Both launchers run disjoint downstream paths over a cached
      // `shared` Read; nothing should contend on anything (no per-task
      // contention, no meta-build escalation).
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
        "meta-build-1"
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

      // Launcher 1 acquired everything first.
      assertContention(launcher1, Set.empty)
      // Launcher 2 must contend on exactly meta-build-1 (the build.mill
      // edit forces a real meta-build rebuild) and nothing else.
      assertContention(
        launcher2,
        Set(blockedLine("runHoldMetaBuildRead", blockerPid, "meta-build-1"))
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

        // Launcher 2: a different Task.Command on the same project. The
        // build is unchanged, so the meta-build is fully reusable; launcher 2
        // should only need Read leases on meta-build depths, which must
        // coexist with launcher 1's retained Reads. With the bug, launcher 2
        // escalates to Write on a meta-build lock and blocks behind
        // launcher 1's retained Read.
        val launcher2 = spawn(("runFast"))
        assertEventually(!launcher2.process.isAlive())

        assert(launcher2.process.exitCode() == 0)
        launcher2.assertContainsLines("fast-value-0")

        release(metaGate)
        launcher1.process.waitFor()

        assert(launcher1.process.exitCode() == 0)
        launcher1.assertContainsLines("fast-value-0")

        // Both launchers must run with no contention at all: meta-build is
        // fully reusable and the per-task work is disjoint (or cached). Any
        // wait — meta-build or per-task — fails the test.
        assertContention(launcher1, Set.empty)
        assertContention(launcher2, Set.empty)
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
      assertEventually(blockedBy(launcher2, "runUseWorker", blockerPid, "workerVersion"))
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

      // Launcher 1 acquired everything first.
      assertContention(launcher1, Set.empty)
      // Launcher 2 must contend on exactly `workerVersion` (the version
      // file changed so the input task needs Write) and nothing else.
      assertContention(
        launcher2,
        Set(blockedLine("runUseWorker", blockerPid, "workerVersion"))
      )
    }
  }
}
