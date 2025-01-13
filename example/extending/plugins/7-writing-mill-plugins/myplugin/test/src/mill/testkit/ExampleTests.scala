package myplugin
import mill.testkit.ExampleTester
import utest._

object ExampleTests extends TestSuite {

  def tests: Tests = Tests {
    test("example") {
      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
      ExampleTester.run(
        clientServerMode = true,
        workspaceSourcePath = resourceFolder / "example-test-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )
    }
  }
}
