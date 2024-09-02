package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object ParseErrorTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test {
      val res = eval("foo.scalaVersion")

      assert(res.isSuccess == false)

      assert(res.err.contains("""bar.mill:14:20 expected ")""""))
      assert(res.err.contains("""println(doesntExist})"""))
      assert(res.err.contains("""qux.mill:3:31 expected ")""""))
      assert(res.err.contains("""System.out.println(doesntExist"""))
    }
  }
}
