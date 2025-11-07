package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration.*
import utest._
import utest.asserts._

object ConcurrentInterruptShutdownTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(10000.millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)
  // Ensure that you can quickly cycle through startup and shutdown without failures.
  val tests: Tests = Tests {
    test("interrupt-blocked") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)
      val launcher1 = spawn(("waitForExists", "--fileName", "file1.txt"))

      def allOutput1 = (launcher1.stdout ++ launcher1.stderr).mkString("\n")

      assertEventually(allOutput1.contains("Waiting on file1.txt"))
      val launcher2 = spawn(("runNow", "--text", "i am cow"))

      def allOutput2 = (launcher2.stdout ++ launcher2.stderr).mkString("\n")

      assertEventually(
        allOutput2.contains(
          "Another Mill process is running 'waitForExists --fileName file1.txt', waiting for it to be done..."
        )
      )
      launcher2.process.destroy(recursive = false)
      assertEventually(!launcher2.process.isAlive())
      os.write(workspacePath / "file1.txt", "Hello world")
      assertEventually(allOutput1.contains("Found file1.txt containing Hello world"))
      assert(!allOutput2.contains("Hello i am cow"))
    }

    test("interrupt-active") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)
      val launcher1 = spawn(("waitForExists", "--fileName", "file1.txt"))

      def allOutput1 = (launcher1.stdout ++ launcher1.stderr).mkString("\n")

      assertEventually(allOutput1.contains("Waiting on file1.txt"))
      val launcher2 = spawn(("runNow", "--text", "i am cow"))

      def allOutput2 = (launcher2.stdout ++ launcher2.stderr).mkString("\n")

      assertEventually(
        allOutput2.contains(
          "Another Mill process is running 'waitForExists --fileName file1.txt', waiting for it to be done..."
        )
      )
      launcher1.process.destroy(recursive = false)
      assertEventually(!launcher1.process.isAlive())
      assert(!(launcher1.stdout ++ launcher1.stderr).exists(_.contains("Found")))
      assertEventually(allOutput2.contains("Hello i am cow"))
    }
  }
}
