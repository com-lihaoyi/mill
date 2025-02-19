package mill.integration

import mill.testkit.{UtestIntegrationTestSuite, IntegrationTester}
import utest._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import utest.asserts.{RetryMax, RetryInterval}

/**
 * Make sure removing the `mill-server` or `mill-no-server` directory
 * kills any running process
 */
object ProcessFileDeletedExit extends UtestIntegrationTestSuite {
  implicit val retryMax: RetryMax = RetryMax(10.seconds)
  implicit val retryInterval: RetryInterval = RetryInterval(1.seconds)
  val tests: Tests = Tests {
    integrationTest { tester =>
      import tester._

      assert(!os.exists(workspacePath / "out/mill-server"))
      assert(!os.exists(workspacePath / "out/mill-no-server"))

      @volatile var watchTerminated = false
      Future {
        eval(
          ("--watch", "foo"),
          stdout = os.ProcessOutput.Readlines { println(_) },
          stderr = os.ProcessOutput.Readlines { println(_) }
        )
        watchTerminated = true
      }

      if (tester.clientServerMode) eventually { os.exists(workspacePath / "out/mill-server") }
      else eventually { os.exists(workspacePath / "out/mill-no-server") }

      assert(watchTerminated == false)

      val processRoot =
        if (tester.clientServerMode) workspacePath / "out/mill-server"
        else workspacePath / "out/mill-no-server"

      os.list(processRoot).map(p => os.remove(p / "processId"))

      eventually { watchTerminated == true }
    }
  }
}
