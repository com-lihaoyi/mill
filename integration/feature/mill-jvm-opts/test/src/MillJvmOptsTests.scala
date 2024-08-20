package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object MillJvmOptsTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()
    "JVM options from file .mill-jvm-opts are properly read" - {
      assert(eval("checkJvmOpts").isSuccess)
    }
  }
}
