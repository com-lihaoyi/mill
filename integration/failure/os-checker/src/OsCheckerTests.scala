package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object OsCheckerTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = tester.eval("foo")

      assert(res.isSuccess == false)
      assert(res.err.contains(s"Writing to disk not allowed during resolution phase to $workspacePath"))

      os.write(workspacePath / "file.txt", "")
      val res2 = tester.eval("nested.bar")

      assert(res2.isSuccess == false)
      assert(res2.err.contains(s"Writing to disk not allowed during resolution phase to $workspacePath/nested"))
    }
  }
}
