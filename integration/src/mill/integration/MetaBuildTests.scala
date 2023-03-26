package mill.integration

import utest._

import scala.util.matching.Regex

class MetaBuildTests(fork: Boolean, clientServer: Boolean)
  extends IntegrationTestSuite("meta-build", fork, clientServer) {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    def mangleFile(p: os.Path, f: String => String) = os.write.over(p, f(os.read(p)))

    def runAssertSuccess() = {
      val res = evalStdout("foo.run")
      assert(res.isSuccess == true)
      // Don't check foo.run stdout in local mode, because it the subprocess
      // println is not properly captured by the test harness
      if (fork) assert(res.out.contains("<h1>hello</h1><p>world</p>"))
    }

    test("success"){
      runAssertSuccess()
    }
  }
}
