package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object RootSubfolderModuleCollisionTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("cannot override final member"))
      assert(res.err.contains(
        " final lazy val sub: _root_.build_.sub.package_.type = _root_.build_.sub.package_ // subfolder module referenc"
      ))
    }
  }
}
