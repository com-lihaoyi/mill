package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ImportRepoTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      // Make sure, we properly parse a line:
      // ```
      // //| repositories: [file://$PWD/custom-repo/testrepo]
      // ```
      // and use it as additional repository
      val result = eval(("parseJson", "--json-string", """{"a": 1, "b": 2, "c": 3}"""))
      assert(result.isSuccess)
      pprint.log(result)

    }
  }
}
