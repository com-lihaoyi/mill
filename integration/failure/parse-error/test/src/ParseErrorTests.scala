package mill.integration

import mill.util.Util
import utest._

object ParseErrorTests extends IntegrationTestSuite {
  val tests = Tests {
    initWorkspace()

    test {
      val res = evalStdout("foo.scalaVersion")

      assert(res.isSuccess == false)

      assert(res.err.contains("""bar.sc:4:20 expected ")""""))
      assert(res.err.contains("""println(doesntExist})"""))
      assert(res.err.contains("""qux.sc:3:31 expected ")""""))
      assert(res.err.contains("""System.out.println(doesntExist"""))
    }
  }
}
