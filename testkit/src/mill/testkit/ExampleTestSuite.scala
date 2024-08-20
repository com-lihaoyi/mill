package mill.testkit
import utest._

object ExampleTestSuite extends IntegrationTestSuite {
  val tests: Tests = Tests {
    val exampleTester = new ExampleTester(clientServerMode, workspaceSourcePath, millExecutable)

    test("exampleTest") {
      exampleTester.run()
    }
  }
}
