package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object MillJvmOptsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("JVM options from file .mill-jvm-opts are properly read") - integrationTest { tester =>
      import tester._
      assert(eval("checkJvmOpts").isSuccess)
    }
  }
}
