package mill.testkit
import utest._

object ExampleTestSuite extends IntegrationTestSuite {
  val tests: Tests = Tests {

    test("exampleTest") {
      new ExampleTester(this).run()
    }
  }
}
