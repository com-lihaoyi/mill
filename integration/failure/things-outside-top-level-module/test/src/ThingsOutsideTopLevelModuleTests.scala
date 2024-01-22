package mill.integration

import utest._

object ThingsOutsideTopLevelModuleTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = evalStdout("resolve", "_")
      assert(!res.isSuccess)
      assert(
        res.err.contains(
          "RootModule bar cannot have other modules defined outside of it: invalidModule"
        )
      )
    }
  }
}
