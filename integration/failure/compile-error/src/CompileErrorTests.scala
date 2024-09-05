package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object CompileErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("foo.scalaVersion")

      assert(res.isSuccess == false)
      assert(res.err.contains("""bar.mill:15:9: not found: value doesntExist"""))
      assert(res.err.contains("""println(doesntExist)"""))
      assert(res.err.contains("""qux.mill:4:34: type mismatch;"""))
      assert(res.err.contains(
        """build.mill:9:5: value noSuchMethod is not a member"""
      ))
    }
  }
}
