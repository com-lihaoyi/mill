package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import scala.concurrent.duration.*
import utest._
import utest.asserts._

object ConcurrentInterruptShutdownTests extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax((if (sys.env.contains("CI")) 120000 else 15000).millis)
  implicit val retryInterval: RetryInterval = RetryInterval(50.millis)

  val tests: Tests = Tests {
    test("interrupt-blocked") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)
      val launcher1 = spawn(("waitForExists", "--fileName", "file1.txt"))

      assertEventually(launcher1.stdout.text().contains("Waiting on file1.txt"))
      val launcher2 = spawn(("runNow", "--text", "i am cow"))

      assertEventually(
        launcher2.stderr.text().contains(
          "Another Mill process is running 'waitForExists --fileName file1.txt', waiting for it to be done..."
        )
      )
      launcher2.process.destroy(recursive = false)
      assertEventually(!launcher2.process.isAlive())
      os.write(workspacePath / "file1.txt", "Hello world")
      assertEventually(launcher1.stdout.text().contains("Found file1.txt containing Hello world"))
      assert(!launcher1.stdout.text().contains("Hello i am cow"))
    }

    test("interrupt-active") - integrationTest { tester =>
      import tester.*
      assert(tester.daemonMode)
      val launcher1 = spawn(("waitForExists", "--fileName", "file1.txt"))

      assertEventually(launcher1.stdout.text().contains("Waiting on file1.txt"))
      val launcher2 = spawn(("runNow", "--text", "i am cow"))

      assertEventually(
        launcher2.stderr.text().contains(
          "Another Mill process is running 'waitForExists --fileName file1.txt', waiting for it to be done..."
        )
      )
      launcher1.process.destroy(recursive = false)
      assertEventually(!launcher1.process.isAlive())
      assert(!(launcher1.stdout.text() + launcher1.stderr.text()).contains("Found"))
      assertEventually(launcher2.stdout.text().contains("Hello i am cow"))
    }
  }
}
