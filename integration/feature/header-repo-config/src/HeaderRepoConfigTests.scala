package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object HeaderRepoConfigTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      val result = eval(("parseJson", "--json-string", """{"a": 1, "b": 2, "c": 3}"""))
      assert(result.isSuccess)
      assert(result.out ==
        """Key: a, Value: 1
          |Key: b, Value: 2
          |Key: c, Value: 3""".stripMargin)
    }
  }
}
