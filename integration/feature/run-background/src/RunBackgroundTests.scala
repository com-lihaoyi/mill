package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object RunBackgroundTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester.*
      // Make sure that logs from `runBackground` subprocesses are picked up by Mill
      // and shown to the user even when the `runBackground` task itself has completed
      val res1 = eval(("--watch", "runMainBackground", "test.BackgroundMain"))
      assert(res1.out.contains("runBackground out logs"))
      assert(res1.err.contains("runBackground err logs"))
    }
  }
}
