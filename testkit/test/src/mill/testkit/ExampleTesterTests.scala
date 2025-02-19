package mill.testkit

import utest._

object ExampleTesterTests extends TestSuite {

  def tests: Tests = Tests {
    test("fork") {
      ExampleTester.run(
        clientServerMode = false,
        workspaceSourcePath =
          os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "example-test-example-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )
    }
    test("server") {
      ExampleTester.run(
        clientServerMode = true,
        workspaceSourcePath =
          os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "example-test-example-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )
    }
  }
}
