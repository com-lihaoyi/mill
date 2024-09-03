package mill.testkit
import utest._

object ExampleTestSuite extends IntegrationTestSuiteBase {
  val tests: Tests = Tests {

    test("exampleTest") {
      new ExampleTester(clientServerMode, workspaceSourcePath, millExecutable).run()
    }
  }
}
