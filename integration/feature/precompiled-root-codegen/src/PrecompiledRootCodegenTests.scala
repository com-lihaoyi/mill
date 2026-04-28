package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object PrecompiledRootCodegenTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("recursiveWildcardFindsPrecompiledYamlModules") - integrationTest { tester =>
      import tester.*

      val res1 = eval(("resolve", "foo._"))
      assert(res1.isSuccess)

      val res2 = eval(("__.compile"))
      assert(res2.isSuccess)
    }
  }
}
