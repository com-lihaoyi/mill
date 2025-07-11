package mill.testkit

import utest._

object ExampleTesterTests extends TestSuite {

  def tests: Tests = Tests {
    test("nodaemon") {
      val workspacePath = ExampleTester.run(
        daemonMode = false,
        workspaceSourcePath =
          os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "example-test-example-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )

      assert(os.exists(workspacePath / "out/mill-no-daemon"))

      assert(TestkitTestUtils.getProcessIdFiles(workspacePath).isEmpty)
    }

    test("daemon") {
      val workspacePath = ExampleTester.run(
        daemonMode = true,
        workspaceSourcePath =
          os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "example-test-example-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      )

      assert(os.exists(workspacePath / "out/mill-daemon"))

      assert(TestkitTestUtils.getProcessIdFiles(workspacePath).isEmpty)
    }
  }
}
