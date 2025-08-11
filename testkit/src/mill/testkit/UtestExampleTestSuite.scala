package mill.testkit

import mill.util.Retry
import utest._

import java.util.concurrent.TimeoutException

import scala.concurrent.duration.DurationInt

object UtestExampleTestSuite extends TestSuite {
  val workspaceSourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  val daemonMode: Boolean = sys.env("MILL_INTEGRATION_DAEMON_MODE").toBoolean

  val millExecutable: os.Path = os.Path(System.getenv("MILL_INTEGRATION_LAUNCHER"), os.pwd)
  val tests: Tests = Tests {

    test("exampleTest") {
      def run() =
        Retry(
          logger = Retry.printStreamLogger(System.err),
          count = if (sys.env.contains("CI")) 1 else 0,
          timeoutMillis = 15.minutes.toMillis
        ) {
          ExampleTester.run(
            daemonMode,
            workspaceSourcePath,
            millExecutable
          )
        }

      val ignoreErrors = System.getenv("CI") != null &&
        os.exists(workspaceSourcePath / "ignoreErrorsOnCI")
      if (ignoreErrors)
        try run()
        catch {
          case _: TimeoutException =>
            System.err.println(
              s"Found ignoreErrorsOnCI under $workspaceSourcePath, ignoring timeout exception"
            )
        }
      else
        run()
    }
  }
}
