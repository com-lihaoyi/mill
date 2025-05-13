package mill.testkit

import utest._

object ExampleTesterTests extends TestSuite {

  def tests: Tests = Tests {
    test("fork") {
      val workspacePath = ExampleTester.run(
        clientServerMode = false,
        workspaceSourcePath =
          os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "example-test-example-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )

      assert(os.exists(workspacePath / "out/mill-no-server"))

      assert(TestkitTestUtils.getProcessIdFiles(workspacePath).isEmpty)
    }

    test("server") {
      val workspacePath = ExampleTester.run(
        clientServerMode = true,
        workspaceSourcePath =
          os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "example-test-example-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )

      assert(os.exists(workspacePath / "out/mill-server"))

      assert(TestkitTestUtils.getProcessIdFiles(workspacePath).isEmpty)
    }
  }
}
