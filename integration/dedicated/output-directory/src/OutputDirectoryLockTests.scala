package mill.integration

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
      val blocker = spawn(("show", "blockWhileExists", "--path", signalFile1))

      // Wait for blocking task to write signal file, to indicate it has begun
      assertEventually { os.exists(signalFile1) }

      if (tester.daemonMode) {
        // Different task should be able to run while the blocker holds its task lock
        val helloRes = eval(("show", "hello"), check = true)
        assert(helloRes.out.contains("Hello from hello task"))

        // Same task should fail immediately in no-wait mode
        val noWaitRes = eval(
          ("--no-wait-for-build-lock", "show", "blockWhileExists", "--path", signalFile2)
        )
        assert(
          noWaitRes.err.contains("Another Mill command in the current daemon is using resource")
        )

        // Same task should wait by default
        val spawnedWaitingRes = spawn(("show", "blockWhileExists", "--path", signalFile2))

        assertEventually {
          spawnedWaitingRes.process.isAlive() && !os.exists(signalFile2)
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
        val spawnedWaitingRes = spawn(("show", "writeMarker", "--path", waitingCompleteFile))

        assertEventually {
          val stderrText = spawnedWaitingRes.err.text()
          stderrText.contains("Another Mill process with PID ") &&
          stderrText.contains(
            s" is running 'show blockWhileExists --path $signalFile1', waiting for it to be done..."
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
