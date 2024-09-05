package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object MillJvmOptsTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    test("JVM options from file .mill-jvm-opts are properly read") - integrationTest { tester => import tester._
      assert(eval("checkJvmOpts").isSuccess)
    }
  }
}
