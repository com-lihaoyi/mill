package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object InvalidPackageDeclaration extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      val res = tester.eval(("resolve", "_"))
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
