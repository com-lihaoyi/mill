package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object PackageFileInRootTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("Mill package.mill files can only be in subfolders"))
    }
  }
}
