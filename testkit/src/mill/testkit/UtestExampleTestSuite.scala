package mill.testkit
import mill.api.Retry
import utest._

import scala.concurrent.duration.DurationInt

object UtestExampleTestSuite extends TestSuite {
  val workspaceSourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  val clientServerMode: Boolean = sys.env("MILL_INTEGRATION_SERVER_MODE").toBoolean

  val millExecutable: os.Path = os.Path(System.getenv("MILL_INTEGRATION_LAUNCHER"), os.pwd)
  val tests: Tests = Tests {

    test("exampleTest") {
      Retry(
        count = if (sys.env.contains("CI")) 1 else 0,
        timeoutMillis = 15.minutes.toMillis
      ) {
        ExampleTester.run(
          clientServerMode,
          workspaceSourcePath,
          millExecutable
        )
      }
    }
  }
}
