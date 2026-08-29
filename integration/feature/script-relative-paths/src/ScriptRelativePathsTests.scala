package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object ScriptRelativePathsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("taskDestScriptRunsFromModuleDir") - integrationTest { tester =>
      val result = tester.eval("Scripts.runScript")

      assert(
        result.isSuccess,
        result.out.contains("hi from Script"),
        result.out.contains("hi from Mill")
      )
    }
  }
}
