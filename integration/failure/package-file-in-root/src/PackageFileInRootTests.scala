package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object PackageFileInRootTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester._
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("Mill package.mill files can only be in subfolders"))
    }
  }
}
