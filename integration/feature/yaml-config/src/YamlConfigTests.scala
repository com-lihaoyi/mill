package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlConfigTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval(("run"))
      pprint.log(res)
    }
  }
}
