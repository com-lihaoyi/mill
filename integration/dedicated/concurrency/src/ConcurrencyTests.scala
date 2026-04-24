package mill.integration

import mill.constants.{DaemonFiles, OutFiles}
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*
import utest.asserts.{RetryInterval, RetryMax}

import scala.concurrent.duration.DurationInt

object ConcurrencyTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(60000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)

  private def waitFile(tester: IntegrationTester.Impl, name: String) = tester.workspacePath / name
  private def enteredMarker(name: String) = s"entered-$name"

  private def blockedBy(
      launcher: mill.testkit.IntegrationTester.SpawnedProcess,
      command: String,
      pid: Long
  ): Boolean =
    combinedText(launcher).contains(
      s"Another Mill command in the current daemon is running '$command' with PID $pid, waiting for it to be done..."
    )

  private def activeLauncherPid(tester: IntegrationTester.Impl, command: String): Option[Long] = {
    val launcherRuns =
      tester.workspacePath / "out" / OutFiles.millDaemon / os.RelPath(DaemonFiles.launcherRuns)
    if (!os.exists(launcherRuns)) None
    else {
      os.list(launcherRuns).iterator
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

  private def combinedText(launcher: mill.testkit.IntegrationTester.SpawnedProcess): String =
    launcher.out.text() + "\n" + launcher.err.text()

  private def release(waitFile: os.Path): Unit =
    if (os.exists(waitFile)) os.remove(waitFile)

  /**
   * Spawn a Mill subprocess with an explicit `--no-daemon` flag, bypassing the
   * tester's `daemonMode`. Used to exercise coarse-grained no-daemon locking
   * paths from this daemon-mode suite.
   */
  private def spawnNoDaemon(
      tester: IntegrationTester.Impl,
      args: String*
  ): IntegrationTester.SpawnedProcess = {
    val chunks = collection.mutable.Buffer.empty[Either[geny.Bytes, geny.Bytes]]
    val stdout = os.ProcessOutput.ReadBytes { (arr, n) =>
      System.out.write(arr, 0, n)
      chunks.synchronized { chunks += Left(new geny.Bytes(arr.take(n))) }
    }
    val stderr = os.ProcessOutput.ReadBytes { (arr, n) =>
      System.err.write(arr, 0, n)
      chunks.synchronized { chunks += Right(new geny.Bytes(arr.take(n))) }
    }
    val process = os.spawn(
      cmd = (tester.millExecutable, "--no-daemon", "--ticker", "false", args),
      env = tester.millTestSuiteEnv,
      cwd = tester.workspacePath,
      stdout = stdout,
      stderr = stderr
    )
    IntegrationTester.SpawnedProcess(process, chunks)
  }

  // Two `--no-daemon` launchers don't share their `launcher-runs` directory
  // (each has its own process-scoped `out/mill-no-daemon/<sig>/launcher-runs`),
  // and a no-daemon launcher blocked by an active daemon also looks in its own
  // per-process dir rather than `out/mill-daemon/launcher-runs`. So the command
  // and PID of the blocker are reported as `<unknown>`. That's the coarse-grained
  // no-daemon fallback — this matcher just asserts that fallback fired.
  private def coarseBlockMessage(
      launcher: mill.testkit.IntegrationTester.SpawnedProcess
  ): Boolean =
    combinedText(launcher).contains(
      "Another Mill process with PID <unknown> is running '<unknown>', waiting for it to be done..."
    )

  val tests: Tests = Tests {
    test("same-task-write-lock-blocks-second-launcher") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      val gate = waitFile(tester, "same-task-wait")
      os.write.over(gate, "")

      val launcher1 = spawn(("runSameTask"))
      assertEventually(combinedText(launcher1).contains(enteredMarker("same-task")))
      val blockerPid = awaitActiveLauncherPid(tester, "runSameTask")

      val launcher2 = spawn(("runSameTask"))
      assertEventually(blockedBy(launcher2, "runSameTask", blockerPid))
      assert(launcher2.process.isAlive())
      assert(!combinedText(launcher2).contains("same-task-value"))

      release(gate)
      launcher1.process.waitFor()
      launcher2.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      assert(launcher2.process.exitCode() == 0)
      assert(launcher1.out.text().contains("same-task-value"))
      assert(launcher2.out.text().contains("same-task-value"))
    }

    test("downstream-holds-upstream-write-lock-while-computing") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      val gate = waitFile(tester, "shared-wait")
      os.write.over(gate, "")

      val launcher1 = spawn(("runLeft"))
      assertEventually(combinedText(launcher1).contains(enteredMarker("shared")))
      val blockerPid = awaitActiveLauncherPid(tester, "runLeft")

      val launcher2 = spawn(("runShared"))
      assertEventually(blockedBy(launcher2, "runLeft", blockerPid))
      assert(launcher2.process.isAlive())
      assert(!combinedText(launcher2).contains("shared-value"))

      release(gate)
      launcher1.process.waitFor()
      launcher2.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      assert(launcher2.process.exitCode() == 0)
      assert(launcher1.out.text().contains("shared-value-left"))
      assert(launcher2.out.text().contains("shared-value"))
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
      assertEventually(combinedText(launcher1).contains(enteredMarker("left")))

      val launcher2 = spawn(("runRight"))
      assertEventually(combinedText(launcher2).contains(enteredMarker("right")))
      assert(!combinedText(launcher2).contains("Another Mill command in the current daemon"))

      release(rightGate)
      launcher2.process.waitFor()

      assert(launcher1.process.isAlive())
      assert(launcher2.process.exitCode() == 0)
      assert(launcher2.out.text().contains("shared-value-right"))
      assert(!combinedText(launcher2).contains("Another Mill command in the current daemon"))

      release(leftGate)
      launcher1.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      assert(launcher1.out.text().contains("shared-value-left"))
      assert(!combinedText(launcher1).contains("Another Mill command in the current daemon"))
    }

    test("meta-build-write-update-blocked-by-active-meta-build-read") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      val gate = waitFile(tester, "meta-build-read-wait")
      os.write.over(gate, "")

      val launcher1 = spawn(("runHoldMetaBuildRead"))
      assertEventually(combinedText(launcher1).contains(enteredMarker("meta-build-read")))
      val blockerPid = awaitActiveLauncherPid(tester, "runHoldMetaBuildRead")

      modifyFile(
        workspacePath / "build.mill",
        _.replace("val fastSuffix = 0", "val fastSuffix = 1")
      )

      val launcher2 = spawn(("runShared"))
      assertEventually(blockedBy(launcher2, "runHoldMetaBuildRead", blockerPid))
      assert(launcher2.process.isAlive())
      assert(!combinedText(launcher2).contains("shared-value"))

      release(gate)
      launcher1.process.waitFor()
      launcher2.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      assert(launcher2.process.exitCode() == 0)
      assert(launcher1.out.text().contains("fast-value-0"))
      assert(launcher2.out.text().contains("shared-value"))
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
      assertEventually(combinedText(launcher1).contains(enteredMarker("worker-use")))
      val blockerPid = awaitActiveLauncherPid(tester, "runUseWorker")

      os.write.over(versionFile, "1")
      val launcher2 = spawn(("runUseWorker"))
      assertEventually(blockedBy(launcher2, "runUseWorker", blockerPid))
      assert(launcher2.process.isAlive())
      assert(!os.exists(closeMarker))

      release(gate)
      launcher1.process.waitFor()
      assertEventually(os.exists(closeMarker))

      release(gate)
      launcher2.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      assert(launcher2.process.exitCode() == 0)
      assert(launcher1.out.text().contains("worker-value-0"))
      assert(launcher2.out.text().contains("worker-value-1"))
    }

    test("two-no-daemon-launchers-block-on-each-other") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      val gate = waitFile(tester, "shared-wait")
      os.write.over(gate, "")

      // Both launchers run as separate --no-daemon processes. They fall back to
      // the coarse out-directory file lock, so the second launcher blocks on the
      // first even though there's no fine-grained contention at the task level.
      val launcher1 = spawnNoDaemon(tester, "runShared")
      assertEventually(combinedText(launcher1).contains(enteredMarker("shared")))

      val launcher2 = spawnNoDaemon(tester, "runShared")
      assertEventually(coarseBlockMessage(launcher2))
      assert(launcher2.process.isAlive())
      assert(!combinedText(launcher2).contains("shared-value"))

      release(gate)
      launcher1.process.waitFor()
      launcher2.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      assert(launcher2.process.exitCode() == 0)
      assert(launcher1.out.text().contains("shared-value"))
      assert(launcher2.out.text().contains("shared-value"))
    }

    test("daemon-launcher-blocks-no-daemon-launcher-on-unrelated-task") - integrationTest {
      tester =>
        import tester.*
        assert(tester.daemonMode)

        val sharedGate = waitFile(tester, "shared-wait")
        os.write.over(sharedGate, "")

        // Launcher1 runs under the daemon, which holds the out-directory file
        // lock for its entire lifetime. Launcher2 is a --no-daemon process that
        // runs an unrelated task, yet it still has to wait: coarse-grained
        // no-daemon processes serialize with the whole daemon, not just with the
        // task they would otherwise contend on.
        val launcher1 = spawn(("runShared"))
        assertEventually(combinedText(launcher1).contains(enteredMarker("shared")))

        os.write.over(workspacePath / "worker-version", "0")
        val launcher2 = spawnNoDaemon(tester, "runWorkerValue")
        assertEventually(coarseBlockMessage(launcher2))
        assert(launcher2.process.isAlive())
        assert(!combinedText(launcher2).contains("worker-value"))

        // Freeing launcher1 doesn't unblock launcher2: the daemon outlives the
        // launcher and keeps the file lock held. Tear launcher2 down explicitly
        // so it doesn't hang past the daemon's shutdown at test cleanup.
        release(sharedGate)
        launcher1.process.waitFor()
        assert(launcher1.process.exitCode() == 0)
        assert(launcher1.out.text().contains("shared-value"))
        assert(launcher2.process.isAlive())

        launcher2.process.destroy()
        launcher2.process.waitFor()
    }
  }
}
