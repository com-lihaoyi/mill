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
  private def enteredMarker(name: String) = s"entered-$name"

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
      assertEventually(launcher1.containsLines(enteredMarker("same-task")))
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
    }

    test("downstream-holds-upstream-write-lock-while-computing") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      val gate = waitFile(tester, "shared-wait")
      os.write.over(gate, "")

      val launcher1 = spawn(("runLeft"))
      assertEventually(launcher1.containsLines(enteredMarker("shared")))
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
      assertEventually(launcher1.containsLines(enteredMarker("left")))
      val blockerPid = awaitActiveLauncherPid(tester, "runLeft")

      val launcher2 = spawn(("runRight"))
      assertEventually(launcher2.containsLines(enteredMarker("right")))
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
    }

    test("meta-build-write-update-blocked-by-active-meta-build-read") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      val gate = waitFile(tester, "meta-build-read-wait")
      os.write.over(gate, "")

      val launcher1 = spawn(("runHoldMetaBuildRead"))
      assertEventually(launcher1.containsLines(enteredMarker("meta-build-read")))
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
      assertEventually(launcher1.containsLines(enteredMarker("worker-use")))
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
    }
  }
}
