package mill.integration

import mill.constants.Util
import mill.testkit.{UtestIntegrationTestSuite, IntegrationTester}
import utest._

import scala.collection.mutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.concurrent.duration.SECONDS
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
          ("--watch", "version"),
          stdout = os.ProcessOutput.Readlines { println(_) },
          stderr = os.ProcessOutput.Readlines { println(_) }
        )
        watchTerminated = true
      }

      if (clientServerMode) eventually {os.exists(workspacePath / "out/mill-server") }
      else eventually{os.exists(workspacePath / "out/mill-no-server")}
      assert(watchTerminated == false)

      if (clientServerMode) os.remove.all(workspacePath / "out/mill-server")
      else os.remove.all(workspacePath / "out/mill-no-server")

      eventually { watchTerminated == true }
    }
  }
}
