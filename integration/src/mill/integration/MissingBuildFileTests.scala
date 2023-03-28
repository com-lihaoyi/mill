
package mill.integration

import utest._

class MissingBuildFileTests(fork: Boolean, clientServer: Boolean)
  extends IntegrationTestSuite("missing-build-file", fork, clientServer) {
  val tests = Tests {
    initWorkspace()

    test {
      val res = evalStdout("resolve", "_")
      assert(!res.isSuccess)
      assert(!res.err.contains("build.sc file not found. Are you in a Mill project folder"))
    }
  }
}
