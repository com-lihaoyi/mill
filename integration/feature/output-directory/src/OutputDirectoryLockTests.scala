package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._
import utest.asserts.{RetryInterval, RetryMax}

import java.util.concurrent.Executors
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, ExecutionContext, Future}

object OutputDirectoryLockTests extends UtestIntegrationTestSuite {

  private val pool = Executors.newCachedThreadPool()
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutorService(pool)

  override def utestAfterAll(): Unit = {
    pool.shutdown()
  }
  implicit val retryMax: RetryMax = RetryMax(60000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)
  def tests: Tests = Tests {
    test("basic") - integrationTest { tester =>
      import tester._
      val signalFile = workspacePath / "do-wait"
      // Kick off blocking task in background
      Future {
        eval(
          ("show", "blockWhileExists", "--path", signalFile),
          check = true,
          stdout = os.Inherit,
          stderr = os.Inherit
        )
      }

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
      val waitingLogFile = workspacePath / "waitingLogFile"
      val waitingCompleteFile = workspacePath / "waitingCompleteFile"
      val futureWaitingRes = Future {
        eval(
          ("show", "writeMarker", "--path", waitingCompleteFile),
          stderr = waitingLogFile,
          check = true
        )
      }

      // Ensure we see the waiting message
      assertEventually {
        os.read(waitingLogFile)
          .contains(
            s"Another Mill process is running 'show blockWhileExists --path $signalFile', waiting for it to be done..."
          )
      }

      // Even after task starts waiting on blocking task, it is not complete
      assert(!futureWaitingRes.isCompleted)
      assert(!os.exists(waitingCompleteFile))
      // Terminate blocking task, make sure waiting task now completes
      os.remove(signalFile)
      val waitingRes = Await.result(futureWaitingRes, Duration.Inf)
      assert(os.exists(waitingCompleteFile))
      assert(waitingRes.out == "\"Write marker done\"")
    }
  }
}
