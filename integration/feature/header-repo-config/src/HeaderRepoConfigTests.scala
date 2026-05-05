package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object HeaderRepoConfigTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester.*
      val result = eval(("parseJson", "--json-string", """[1, 2, 3]"""))
      assert(result.isSuccess)
      assert(result.out ==
        """Value: 1
          |Value: 2
          |Value: 3""".stripMargin)
    }
  }
}
