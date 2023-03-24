
package mill.integration

import mill.util.Util
import utest._

class CompileErrorTests(fork: Boolean, clientServer: Boolean)
  extends IntegrationTestSuite("compile-error", fork, clientServer) {
  def captureOutErr = true
  val tests = Tests {
    initWorkspace()

    test {
      val (res, out, err) = evalStdout("foo.scalaVersion")
      assert(res == false)
      val errorString = err.mkString("\n")
      assert(errorString.contains("""bar.sc:4:9: not found: value doesntExist"""))
      assert(errorString.contains("""println(doesntExist)"""))
      assert(errorString.contains("""qux.sc:3:34: type mismatch;"""))
      assert(errorString.contains("""build.sc:8:5: value noSuchMethod is not a member of object build.this.foo"""))
    }
  }
}
