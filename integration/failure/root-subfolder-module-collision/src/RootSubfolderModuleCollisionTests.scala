package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object RootSubfolderModuleCollisionTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      import tester._
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("cannot override final member"))
      assert(res.err.contains(
        " final lazy val sub: _root_.build_.sub.package_.type = _root_.build_.sub.package_ // subfolder module referenc"
      ))
    }
  }
}
