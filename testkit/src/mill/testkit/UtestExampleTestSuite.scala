package mill.testkit
import mill.api.Retry
import utest._

import scala.concurrent.duration.DurationInt

object UtestExampleTestSuite extends TestSuite {
  val workspaceSourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))
  val clientServerMode: Boolean = sys.env("MILL_INTEGRATION_SERVER_MODE").toBoolean

  val millExecutable: os.Path = os.Path(System.getenv("MILL_INTEGRATION_LAUNCHER"), os.pwd)
  val tests: Tests = Tests {

    test("exampleTest") {
      if (sys.env.contains("CI")) {
        Retry(count = 3, timeoutMillis = 5.minutes.toMillis) {
          ExampleTester.run(
            clientServerMode,
            workspaceSourcePath,
            millExecutable
          )
        }
      } else {
        ExampleTester.run(
          clientServerMode,
          workspaceSourcePath,
          millExecutable
        )
      }
    }
  }
}
