package mill.testkit
import utest._

object ExampleTestSuite extends IntegrationTestSuite {
  val tests: Tests = Tests {
    val exampleTester = new ExampleTester(
      sys.env("MILL_INTEGRATION_SERVER_MODE").toBoolean,
      os.Path(sys.env("MILL_INTEGRATION_REPO_ROOT"))
    )

    test("exampleTest") {
      exampleTester.run()
    }
  }
}
