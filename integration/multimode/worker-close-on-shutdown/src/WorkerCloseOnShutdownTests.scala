package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration.*
import utest.*
import utest.asserts.*

object WorkerCloseOnShutdownTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax((if (sys.env.contains("CI")) 120000 else 15000).millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)

  val tests: Tests = Tests {
    test("worker-closed-on-process-exit") - integrationTest { tester =>
      import tester.*
      // Worker writes marker files to out/trackedWorker.dest/
      val workerDestDir = workspacePath / "out" / "trackedWorker.dest"

      // Run a command that initializes the worker
      val result = eval("initWorker")
      assert(result.isSuccess)

      // Verify the worker is running (running marker exists) after the command
      // In daemon mode, the worker persists. In non-daemon mode, the worker was
      // created and closed within the same process, so we should see the closed marker.
      if (tester.daemonMode) {
        // In daemon mode, worker should still be running
        assert(os.exists(workerDestDir / "worker-running"))
        assert(!os.exists(workerDestDir / "worker-closed"))

        // Shutdown the daemon to trigger worker cleanup
        eval("shutdown")
        Thread.sleep(500)

        // Verify the worker was closed
        assertEventually(os.exists(workerDestDir / "worker-closed"))
        assert(!os.exists(workerDestDir / "worker-running"))
      } else {
        // In non-daemon mode, the Mill process exits after the command,
        // so the worker should already be closed
        assertEventually(os.exists(workerDestDir / "worker-closed"))
        assert(!os.exists(workerDestDir / "worker-running"))
      }
    }

    test("worker-closed-on-client-destroy") - integrationTest { tester =>
      import tester.*
      // This test only applies to daemon mode - in non-daemon mode there's no
      // daemon to shut down when the client disconnects
      if (!tester.daemonMode) {
        "skipped (non-daemon mode)"
      } else {
        // Worker writes marker files to out/trackedWorker.dest/
        val workerDestDir = workspacePath / "out" / "trackedWorker.dest"
        val triggerFile = workspacePath / "trigger.txt"

        // Spawn a long-running task that uses the worker
        val launcher = spawn(("waitForExists", "--fileName", triggerFile.toString))

        // Wait for the worker to be created
        assertEventually(os.exists(workerDestDir / "worker-running"))
        assert(!os.exists(workerDestDir / "worker-closed"))

        // Destroy the client process
        // When the client disconnects while a task is running, the daemon shuts down
        // and should close all workers
        launcher.process.destroy(recursive = false)
        assertEventually(!launcher.process.isAlive())

        // Verify the worker was closed (daemon should have shut down and cleaned up workers)
        assertEventually(os.exists(workerDestDir / "worker-closed"))
        assert(!os.exists(workerDestDir / "worker-running"))
      }
    }
  }
}
