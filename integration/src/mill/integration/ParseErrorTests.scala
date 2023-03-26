
package mill.integration

import mill.util.Util
import utest._

class ParseErrorTests(fork: Boolean, clientServer: Boolean)
    extends IntegrationTestSuite("parse-error", fork, clientServer) {
  val tests = Tests {
    initWorkspace()

    test {
      evalStdoutAssert("foo.scalaVersion"){res =>

        assert(res.isSuccess == false)
        val errorString = res.errLines.mkString("\n")

        assert(errorString.contains("""bar.sc:4:20 expected ")""""))
        assert(errorString.contains("""println(doesntExist})"""))
        assert(errorString.contains("""qux.sc:3:31 expected ")""""))
        assert(errorString.contains("""System.out.println(doesntExist"""))
      }
    }
  }
}
