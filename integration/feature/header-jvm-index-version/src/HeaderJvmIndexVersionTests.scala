package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object HeaderJvmIndexVersionTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      import tester._
      val result = eval("printJavaVersion")
      assert(result.isSuccess)
      // This example uses an older `mill-jvm-index-version` to ensure the resolved version
      // of `temurin:11` gets appropriately downgraded, default at time of writing would have
      // been 11.0.28
      assert(result.out == "11.0.26")
    }
  }
}
