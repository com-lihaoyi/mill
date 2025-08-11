package mill.integration

import mill.testkit.{UtestIntegrationTestSuite, IntegrationTester}
import utest._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import utest.asserts.{RetryMax, RetryInterval}

/**
 * Make sure removing the `mill-daemon` or `mill-no-daemon` directory
 * kills any running process
 */
object ProcessFileDeletedExit extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(60.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {
    integrationTest { tester =>
      import tester._

      assert(!os.exists(workspacePath / "out/mill-daemon"))
      assert(!os.exists(workspacePath / "out/mill-no-daemon"))

      @volatile var watchTerminated = false
      Future {
        eval(
          ("--watch", "foo"),
          stdout = os.ProcessOutput.Readlines { println(_) },
          stderr = os.ProcessOutput.Readlines { println(_) }
        )
        watchTerminated = true
      }

      if (tester.daemonMode) assertEventually { os.exists(workspacePath / "out/mill-daemon") }
      else assertEventually { os.exists(workspacePath / "out/mill-no-daemon") }

      assert(watchTerminated == false)

      val processRoot =
        if (tester.daemonMode) workspacePath / "out/mill-daemon"
        else workspacePath / "out/mill-no-daemon"

      assertEventually {
        os.walk(processRoot).exists(_.last == "processId")
      }

      if (tester.daemonMode) {
        os.remove(processRoot / "processId")
      } else {
        os.list(processRoot).map { p =>
          os.remove(p / "processId")
        }
      }

      assertEventually {
        watchTerminated == true
      }
    }
  }
}
