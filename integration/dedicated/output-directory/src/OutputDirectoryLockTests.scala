package mill.integration

import mill.constants.{DaemonFiles, OutFiles}
import mill.testkit.UtestIntegrationTestSuite
import utest.*
import utest.asserts.{RetryInterval, RetryMax}

import scala.concurrent.duration.DurationInt

object OutputDirectoryLockTests extends UtestIntegrationTestSuite {

  implicit val retryMax: RetryMax = RetryMax(60000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)

  private def activeLauncherPid(workspacePath: os.Path, command: String): Option[Long] = {
    val launcherRuns =
      workspacePath / "out" / OutFiles.millDaemon / os.RelPath(DaemonFiles.launcherRuns)
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

  private def awaitActiveLauncherPid(workspacePath: os.Path, command: String): Long = {
    var pid = Option.empty[Long]
    assertEventually {
      pid = activeLauncherPid(workspacePath, command)
      pid.nonEmpty
    }
    pid.get
  }

  def tests: Tests = Tests {
    test("taskLocks") - integrationTest { tester =>
      import tester.*
      val signalFile1 = workspacePath / "do-wait-1"
      val signalFile2 = workspacePath / "do-wait-2"
      val blocker = spawn(("blockWhileExists", "--path", signalFile1))

      // Wait for blocking task to write signal file, to indicate it has begun
      assertEventually { os.exists(signalFile1) }

      if (tester.daemonMode) {
        // Different task should be able to run while the blocker holds its task lock
        val helloRes = eval(("hello"), check = true)
        assert(helloRes.exitCode == 0)

        assertEventually {
          os.isLink(workspacePath / "out" / DaemonFiles.millConsoleTail) &&
          os.isLink(workspacePath / "out" / OutFiles.millProfile) &&
          os.isLink(workspacePath / "out" / OutFiles.millChromeProfile) &&
          os.isLink(workspacePath / "out" / OutFiles.millDependencyTree) &&
          os.isLink(workspacePath / "out" / OutFiles.millInvalidationTree)
        }
        val blockerPid = awaitActiveLauncherPid(
          workspacePath,
          s"blockWhileExists --path $signalFile1"
        )

        // Same task should fail immediately in no-wait mode
        val noWaitRes = eval(
          ("--no-wait-for-build-lock", "blockWhileExists", "--path", signalFile2)
        )
        assert(
          noWaitRes.err.contains(
            s"Another Mill command in the current daemon is running 'blockWhileExists --path $signalFile1' with PID $blockerPid"
          )
        )

        // Same task should wait by default
        val spawnedWaitingRes = spawn(("blockWhileExists", "--path", signalFile2))

        assertEventually {
          val stderrText = spawnedWaitingRes.err.text()
          spawnedWaitingRes.process.isAlive() &&
          !os.exists(signalFile2) &&
          stderrText.contains(
            s"Another Mill command in the current daemon is running 'blockWhileExists --path $signalFile1' with PID $blockerPid, waiting for it to be done..."
          ) &&
          stderrText.contains("tail -F out/mill-console-tail")
        }

        // Terminate blocking task, make sure waiting task now completes
        os.remove(signalFile1)
        blocker.process.waitFor()
        assertEventually { os.exists(signalFile2) }
        os.remove(signalFile2)
        spawnedWaitingRes.process.waitFor()
        assert(!spawnedWaitingRes.process.isAlive())
      } else {
        // In no-daemon mode we keep the coarse global lock
        val waitingCompleteFile = workspacePath / "waitingCompleteFile"
        val spawnedWaitingRes = spawn(("writeMarker", "--path", waitingCompleteFile))

        assertEventually {
          val stderrText = spawnedWaitingRes.err.text()
          stderrText.contains("Another Mill process with PID ") &&
          stderrText.contains(
            s" is running 'blockWhileExists --path $signalFile1', waiting for it to be done..."
          )
        }

        assert(spawnedWaitingRes.process.isAlive())
        assert(!os.exists(waitingCompleteFile))
        os.remove(signalFile1)
        blocker.process.waitFor()
        spawnedWaitingRes.process.waitFor()
        assert(os.exists(waitingCompleteFile))
      }
    }
  }
}
