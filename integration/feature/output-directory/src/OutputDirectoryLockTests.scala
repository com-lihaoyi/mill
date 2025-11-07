package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._
import utest.asserts.{RetryInterval, RetryMax}

import scala.concurrent.duration.DurationInt

object OutputDirectoryLockTests extends UtestIntegrationTestSuite {

  implicit val retryMax: RetryMax = RetryMax(60000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)
  def tests: Tests = Tests {
    test("basic") - integrationTest { tester =>
      import tester._
      val signalFile = workspacePath / "do-wait"
      // Kick off blocking task in background
      spawn(
        ("show", "blockWhileExists", "--path", signalFile)
      )

      // Wait for blocking task to write signal file, to indicate it has begun
      assertEventually { os.exists(signalFile) }

      val testCommand: os.Shellable = ("show", "hello")
      val testMessage = "Hello from hello task"

      // --no-build-lock allows commands to complete despite background blocker
      val noLockRes = eval(("--no-build-lock", testCommand), check = true)
      assert(noLockRes.out.contains(testMessage))

      // --no-wait-for-build-lock causes commands fail due to background blocker
      val noWaitRes = eval(("--no-wait-for-build-lock", testCommand))
      assert(
        noWaitRes
          .err
          .contains(
            s"Another Mill process is running 'show blockWhileExists --path $signalFile', failing"
          )
      )

      // By default, we wait until the background blocking task completes
      val waitingCompleteFile = workspacePath / "waitingCompleteFile"
      val spawnedWaitingRes = spawn(
        ("show", "writeMarker", "--path", waitingCompleteFile)
      )

      // Ensure we see the waiting message
      assertEventually {
        spawnedWaitingRes.stderr.text()
          .contains(
            s"Another Mill process is running 'show blockWhileExists --path $signalFile', waiting for it to be done..."
          )
      }

      // Even after task starts waiting on blocking task, it is not complete
      assert(spawnedWaitingRes.process.isAlive())
      assert(!os.exists(waitingCompleteFile))
      // Terminate blocking task, make sure waiting task now completes
      os.remove(signalFile)
      spawnedWaitingRes.process.waitFor()
      assert(os.exists(waitingCompleteFile))
      assert(spawnedWaitingRes.stdout.trim() == "\"Write marker done\"")
    }
  }
}
