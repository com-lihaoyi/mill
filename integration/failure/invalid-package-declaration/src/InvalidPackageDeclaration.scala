package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object InvalidPackageDeclaration extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test("success") {
      val res = eval(("resolve", "_"))
      assert(res.isSuccess == false)
      assert(res.err.contains(
        """Package declaration "package wrong" in build.mill does not match folder structure. Expected: "package build""""
      ))
      assert(res.err.contains(
        """Package declaration "package " in sub/package.mill does not match folder structure. Expected: "package build.sub""""
      ))
      assert(res.err.contains(
        """Package declaration "package `sub-2`" in sub-2/inner/package.mill does not match folder structure. Expected: "package build.`sub-2`.inner""""
      ))
    }
  }
}
