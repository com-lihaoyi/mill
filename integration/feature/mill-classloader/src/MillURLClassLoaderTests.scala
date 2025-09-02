package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

class MillURLClassLoaderTests extends UtestIntegrationTestSuite {

  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      val result1 = tester.eval(("show", "graphDependencies"))
      // The expected output shows, that we could successfully load the `scala.meta` package
      assert(result1.isSuccess)
      assert(result1.out.contains(""""object Main extends App { print(\"Hello!\") }""""))

    }
  }
}
