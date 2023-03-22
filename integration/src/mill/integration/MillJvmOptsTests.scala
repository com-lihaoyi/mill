package mill.integration

import utest._

class MillJvmOptsTests(fork: Boolean, clientServer: Boolean)
    extends IntegrationTestSuite("mill-jvm-opts", fork, clientServer) {
  val tests = Tests {
    initWorkspace()
    "JVM options from file .mill-jvm-opts are properly read" - {
      assert(eval("checkJvmOpts"))
    }
  }
}
