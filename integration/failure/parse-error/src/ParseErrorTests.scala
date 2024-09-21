package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ParseErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval("foo.scalaVersion")

      assert(res.isSuccess == false)

      assert(res.err.contains("""bar.mill:14:20"""))
      assert(res.err.contains("""')' expected, but '}' found"""))
      assert(res.err.contains("""println(doesntExist})"""))
      assert(res.err.contains("""qux.mill:3:31"""))
      assert(res.err.contains("""')' expected, but eof found"""))
      assert(res.err.contains("""System.out.println(doesntExist"""))
    }
  }
}
