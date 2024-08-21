package mill.testkit

import utest._

object ExampleTesterTests extends TestSuite {

  def tests: Tests = Tests {
    test("example") {
      new ExampleTester(
        clientServerMode = true,
        workspaceSourcePath =
          os.pwd / "testkit" / "test" / "resources" / "example-test-example-project",
        millExecutable = os.Path(sys.env("MILL_EXECUTABLE_PATH"))
      ).run()
    }
  }
}
