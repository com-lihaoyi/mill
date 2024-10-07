package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object OutputDirectoryLockTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {
    test("basic") - integrationTest { tester =>
      import tester._
      val signalFile = workspacePath / "do-wait"
      System.err.println("Spawning blocking task")
      val blocksFuture = evalAsync(("show", "blockWhileExists", "--path", signalFile), check = true)
      while (!os.exists(signalFile) && !blocksFuture.isCompleted)
        Thread.sleep(100L)
      if (os.exists(signalFile))
        System.err.println("Blocking task is running")
      else {
        System.err.println("Failed to run blocking task")
        Predef.assert(blocksFuture.isCompleted)
        blocksFuture.value.get.get
      }

      val testCommand: os.Shellable = ("show", "hello")
      val testMessage = "Hello from hello task"

      System.err.println("Evaluating task without lock")
      val noLockRes = eval(("--no-build-lock", testCommand), check = true)
      assert(noLockRes.out.contains(testMessage))

      System.err.println("Evaluating task without waiting for lock (should fail)")
      val noWaitRes = eval(("--no-wait-for-build-lock", testCommand))
      assert(noWaitRes.err.contains("Cannot proceed, another Mill process is running tasks"))

      System.err.println("Evaluating task waiting for the lock")

      val lock = new CountDownLatch(1)
      val stderr = new ByteArrayOutputStream
      var success = false
      val futureWaitingRes = evalAsync(
        testCommand,
        stderr = os.ProcessOutput {
          val expectedMessage =
            "Another Mill process is running tasks, waiting for it to be done..."

          (bytes, len) =>
            stderr.write(bytes, 0, len)
            val output = new String(stderr.toByteArray)
            if (output.contains(expectedMessage))
              lock.countDown()
        },
        check = true
      )
      try {
        lock.await()
        success = true
      } finally {
        if (!success) {
          System.err.println("Waiting task output:")
          System.err.write(stderr.toByteArray)
        }
      }

      System.err.println("Task is waiting for the lock, unblocking it")
      os.remove(signalFile)

      System.err.println("Blocking task should exit")
      val blockingRes = Await.result(blocksFuture, Duration.Inf)
      assert(blockingRes.out.contains("Blocking command done"))

      System.err.println("Waiting task should be free to proceed")
      val waitingRes = Await.result(futureWaitingRes, Duration.Inf)
      assert(waitingRes.out.contains(testMessage))
    }
  }
}
