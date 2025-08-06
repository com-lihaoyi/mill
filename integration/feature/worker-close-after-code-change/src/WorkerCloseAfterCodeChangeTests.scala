package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object WorkerCloseAfterCodeChangeTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      val result = eval(("show", "bar"))
      assert(result.isSuccess)
      assert(result.out == "123")
      tester.modifyFile(workspacePath / "build.mill", _.replace("123", "456"))

      val result2 = eval(("show", "bar"))
      assert(result2.isSuccess)
      assert(result2.out == "456")

    }
  }
}
