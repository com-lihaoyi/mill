package mill.integration

import utest._

object MillJvmOptsTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()
    "JVM options from file .mill-jvm-opts are properly read" - {
      if (integrationTestMode != "local") assert(eval("checkJvmOpts"))
    }
  }
}
