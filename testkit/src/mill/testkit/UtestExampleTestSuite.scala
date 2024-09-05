package mill.testkit
import utest._

object UtestExampleTestSuite extends TestSuite {
  val workspaceSourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))
  val clientServerMode: Boolean = sys.env("MILL_INTEGRATION_SERVER_MODE").toBoolean

  val millExecutable: os.Path = os.Path(System.getenv("MILL_INTEGRATION_LAUNCHER"), os.pwd)
  val tests: Tests = Tests {

    test("exampleTest") {
      new ExampleTester(clientServerMode, workspaceSourcePath, millExecutable).run()
    }
  }
}
