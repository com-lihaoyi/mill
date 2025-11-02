package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ScriptHeaderChangedToInvalid extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("./Foo.java")
      assert(res.out.contains("Hello"))
      assert(res.isSuccess)

      tester.modifyFile(tester.workspacePath / "Foo.java", _.replace("//", "//|"))

      val res2 = tester.eval("./Foo.java")
      assert(!res2.isSuccess)
    }
  }
}
