package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object RootSubfolderModuleCollision extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("sub is defined twice"))
      assert(res.err.contains("def sub = millbuild.sub.module // subfolder module reference"))
    }
  }
}
