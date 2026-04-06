package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object InvalidPackageDeclaration extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("success") - integrationTest { tester =>
      val res = tester.eval(("resolve", "_"))
      assert(res.isSuccess == false)
      res.assertContainsLines(
        "[error] build.mill:1:1",
        "package wrong",
        "^"
      )
      assert(res.err.contains(
        """Package declaration "package wrong" in build.mill does not match folder structure. Expected: "package build""""
      ))
      res.assertContainsLines(
        "[error] sub/package.mill:1:1",
        "import mill.*",
        "^"
      )
      assert(res.err.contains(
        """Package declaration "package " in sub/package.mill does not match folder structure. Expected: "package build.sub""""
      ))
      res.assertContainsLines(
        "[error] sub-2/inner/package.mill:1:1",
        "package `sub-2`",
        "^"
      )
      assert(res.err.contains(
        """Package declaration "package `sub-2`" in sub-2/inner/package.mill does not match folder structure. Expected: "package build.`sub-2`.inner""""
      ))
    }
  }
}
