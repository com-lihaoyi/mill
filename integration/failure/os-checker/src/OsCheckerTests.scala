package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object OsCheckerTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = tester.eval("foo.bar")

      assert(res.isSuccess == false)
      assert(res.err.contains(s"Writing to disk not allowed during resolution phase to $workspacePath/foo"))

      val res2 = tester.eval("qux")

      assert(res2.isSuccess == false)
      assert(res2.err.contains(s"Writing to disk not allowed during execution phase to $workspacePath/file.txt"))



    }
  }
}
