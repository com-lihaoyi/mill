package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object ParseErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res = eval("foo.scalaVersion")

      assert(res.isSuccess == false)

      res.assertContainsLines(
        "bar.mill:14:20",
        "println(doesntExist})",
        "                   ^",
        "')' expected, but '}' found"
      )
      res.assertContainsLines(
        "qux.mill:3:31",
        "System.out.println(doesntExist",
        "                              ^",
        "')' expected, but eof found"
      )
    }
  }
}
