package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object CompileErrorTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test {
      val res = eval("foo.scalaVersion")

      assert(res.isSuccess == false)
      assert(res.err.contains("""bar.sc:15:9: not found: value doesntExist"""))
      assert(res.err.contains("""println(doesntExist)"""))
      assert(res.err.contains("""qux.sc:4:34: type mismatch;"""))
      assert(res.err.contains(
        """build.sc:8:5: value noSuchMethod is not a member"""
      ))
    }
  }
}
