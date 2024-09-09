import mill.testkit.IntegrationTestSuite
import utest.{assert, *}

object ThingsOutsideTopLevelModuleTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = eval(("resolve", "_"))
      assert(res.isSuccess)
    }
  }
}
