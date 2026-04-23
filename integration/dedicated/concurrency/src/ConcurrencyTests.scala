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
      val commandPattern = """"command"\s*:\s*"([^"]*)"""".r
      val pidPattern = """"pid"\s*:\s*([0-9]+)""".r
      os.list(launcherRuns).iterator
        .filter(os.isFile(_))
        .flatMap { path =>
          val json = os.read(path)
          val fileCommand = commandPattern.findFirstMatchIn(json).map(_.group(1))
          val filePid = pidPattern.findFirstMatchIn(json).flatMap(_.group(1).toLongOption)
          Option.when(fileCommand.contains(command))(filePid).flatten
        }
        .toSeq
        .lastOption
    }
  }

  private def combinedText(launcher: mill.testkit.IntegrationTester.SpawnedProcess): String =
    launcher.out.text() + "\n" + launcher.err.text()

  private def release(waitFile: os.Path): Unit =
    if (os.exists(waitFile)) os.remove(waitFile)

  val tests: Tests = Tests {
    test("same-task-write-lock-blocks-second-launcher") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)

      val gate = waitFile(tester, "same-task-wait")
      os.write.over(gate, "")

      val launcher1 = spawn(("runSameTask"))
      assertEventually(combinedText(launcher1).contains(enteredMarker("same-task")))
      assertEventually(activeLauncherPid(tester, "runSameTask").nonEmpty)
      val blockerPid = activeLauncherPid(tester, "runSameTask").get

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
      assertEventually(activeLauncherPid(tester, "runLeft").nonEmpty)
      val blockerPid = activeLauncherPid(tester, "runLeft").get

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
      assertEventually(activeLauncherPid(tester, "runHoldMetaBuildRead").nonEmpty)
      val blockerPid = activeLauncherPid(tester, "runHoldMetaBuildRead").get

      modifyFile(workspacePath / "build.mill", _ + "\n// force meta-build refresh\n")

      val launcher2 = spawn(("runShared"))
      assertEventually(blockedBy(launcher2, "runHoldMetaBuildRead", blockerPid))
      assert(launcher2.process.isAlive())
      assert(!combinedText(launcher2).contains("shared-value"))

      release(gate)
      launcher1.process.waitFor()
      launcher2.process.waitFor()

      assert(launcher1.process.exitCode() == 0)
      assert(launcher2.process.exitCode() == 0)
      assert(launcher1.out.text().contains("0.13.1"))
      assert(launcher2.out.text().contains("shared-value"))
    }
  }
}
