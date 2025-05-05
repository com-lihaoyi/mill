package myplugin
import mill.testkit.ExampleTester
import utest._

object ExampleTests extends TestSuite {

  def tests: Tests = Tests {
    test("example") {
//      pprint.log(sys.env("MILL_EXECUTABLE_PATH"))
//      pprint.log(sys.env("MILL_LOCAL_TEST_OVERRIDE_CLASSPATH"))
      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
      ExampleTester.run(
        clientServerMode = true,
        workspaceSourcePath = resourceFolder / "example-test-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )
    }
  }
}
