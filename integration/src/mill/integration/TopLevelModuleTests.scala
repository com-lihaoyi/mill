package mill.integration

import utest._

class TopLevelModuleTests(fork: Boolean, clientServer: Boolean)
  extends IntegrationTestSuite("top-level-module", fork, clientServer) {
  val tests = Tests {
    initWorkspace()

    test("success"){
      val res1 = evalStdout("compile")
      assert(res1.isSuccess)
      val res2 = evalStdout("run")
      assert(res2.isSuccess)

      // local integration tests don't properly capture stdout
      if (fork) assert(res2.out.contains("Hello World"))
    }
  }
}
