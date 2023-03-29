
package mill.integration

import mill.util.Util
import utest._

object CompileErrorTests extends IntegrationTestSuite.Cross  {
  def captureOutErr = true
  val tests = Tests {
    initWorkspace()

    test {
      val res = evalStdout("foo.scalaVersion")

      assert(res.isSuccess == false)
      assert(res.err.contains("""bar.sc:4:9: not found: value doesntExist"""))
      assert(res.err.contains("""println(doesntExist)"""))
      assert(res.err.contains("""qux.sc:3:34: type mismatch;"""))
      assert(res.err.contains("""build.sc:8:5: value noSuchMethod is not a member of object build.this.foo"""))
    }
  }
}
