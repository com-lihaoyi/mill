package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object OsCheckerTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = tester.eval("foo.bar")

      assert(res.isSuccess == false)
      assert(res.err.contains(s"Writing to foo not allowed during resolution phase"))

      val res2 = tester.eval("qux")

      assert(res2.isSuccess == false)
      assert(res2.err.contains(s"Writing to file.txt not allowed during execution of `qux`"))

      val res2allowed = tester.eval(("--no-filesystem-checker", "qux"))

      assert(res2allowed.isSuccess)

      tester.modifyFile(workspacePath / "build.mill", _.replace("if (false)", "if (true)"))
      val res3 = tester.eval("baz")

      assert(res3.isSuccess == false)
      assert(res3.err.contains(
        s"Writing to  not allowed during resolution phase"
      ))

      tester.modifyFile(workspacePath / "build.mill", _.replace("if (true)", "if (false)"))
      tester.modifyFile(
        workspacePath / "build.mill",
        _ + "\nprintln(os.read(mill.api.BuildCtx.workspaceRoot / \"build.mill\"))"
      )

      val res4allowed = tester.eval(("--no-filesystem-checker", "allowed.allowedTask"))

      assert(res4allowed.isSuccess)

      tester.eval("shutdown")
      val res4 = tester.eval("baz")

      assert(res4.isSuccess == false)
      assert(res4.err.contains(s"Reading from build.mill not allowed during resolution phase"))

    }
  }
}
