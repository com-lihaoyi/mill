package mill.integration

import mill.constants.{DaemonFiles, OutFiles}
import mill.testkit.UtestIntegrationTestSuite
import utest.*
import utest.asserts.{RetryInterval, RetryMax}

import scala.concurrent.duration.DurationInt

object OutputDirectoryLockTests extends UtestIntegrationTestSuite {

  implicit val retryMax: RetryMax = RetryMax(60000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)
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

        val outPath = workspacePath / "out"
        val consoleTail = outPath / DaemonFiles.millConsoleTail
        val profile = outPath / OutFiles.millProfile
        val chromeProfile = outPath / OutFiles.millChromeProfile
        val active = outPath / OutFiles.millActive

        assertEventually {
          os.exists(consoleTail) &&
          os.exists(profile) &&
          os.exists(chromeProfile) &&
          os.exists(active) &&
          java.nio.file.Files.isSymbolicLink(consoleTail.toNIO) &&
          java.nio.file.Files.isSymbolicLink(profile.toNIO) &&
          java.nio.file.Files.isSymbolicLink(chromeProfile.toNIO) &&
          java.nio.file.Files.isSymbolicLink(active.toNIO)
        }
        assert(os.read(active).contains("blockWhileExists"))

        // Same task should fail immediately in no-wait mode
        val noWaitRes = eval(
          ("--no-wait-for-build-lock", "blockWhileExists", "--path", signalFile2)
        )
        assert(
          noWaitRes.err.contains("Another Mill command in the current daemon is running")
        )

        // Same task should wait by default
        val spawnedWaitingRes = spawn(("blockWhileExists", "--path", signalFile2))

        assertEventually {
          val stderrText = spawnedWaitingRes.err.text()
          spawnedWaitingRes.process.isAlive() &&
          !os.exists(signalFile2) &&
          stderrText.contains("Another Mill command in the current daemon is running") &&
          stderrText.contains("waiting for it to be done") &&
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
