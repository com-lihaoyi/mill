package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object OsCheckerTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
//      val res = tester.eval("foo.bar")
//
//      val sep = if (mill.constants.Util.isWindows) "\\" else "/"
//      assert(res.isSuccess == false)
//      assert(res.err.contains(
//        s"Writing to $workspacePath${sep}foo not allowed during resolution phase"
//      ))
//
//      val res2 = tester.eval("qux")
//
//      assert(res2.isSuccess == false)
//      assert(res2.err.contains(
//        s"Writing to $workspacePath${sep}file.txt not allowed during execution phase"
//      ))

      tester.modifyFile(workspacePath / "build.mill", _.replace("if (false)", "if (true)"))
      val res3 = tester.eval("baz")

      assert(res3.isSuccess == false)
      assert(res3.err.contains(
        s"Writing to $workspacePath not allowed during resolution phase"
      ))

    }
  }
}
