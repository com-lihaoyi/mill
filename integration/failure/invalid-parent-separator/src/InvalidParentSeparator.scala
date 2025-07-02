package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object InvalidParentSeparator extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval(("show", "allSourceFiles"))

      assert(res.isSuccess)
      assert(res.out == "[]")

      tester.modifyFile(
        workspacePath / "build.mill",
        _.replace("with JavaModule", "/**/with JavaModule")
      )

      val res2 = eval(("show", "allSourceFiles"))
      assert(!res2.isSuccess)
      assert(res2.err.contains(
        """superclass `Module` must be followed by "," or " with ", not "/**/with J...""""
      ))
    }
  }
}
