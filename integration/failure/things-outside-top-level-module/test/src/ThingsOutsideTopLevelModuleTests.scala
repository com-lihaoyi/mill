package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object ThingsOutsideTopLevelModuleTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = eval(("resolve", "_"))
      assert(!res.isSuccess)
      assert(
        res.err.contains(
          "Definition not allowed outside body of object `package`"
        )
      )
      assert(
        res.err.contains(
          "def invalidTarget"
        )
      )
    }
  }
}
