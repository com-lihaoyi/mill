package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object RootSubfolderModuleCollision extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains("sub is already defined as object sub"))
      assert(res.err.contains(
        " final lazy val sub: _root_.millbuild.sub.package_.type = _root_.millbuild.sub.package_ // subfolder module reference"
      ))
    }
  }
}
