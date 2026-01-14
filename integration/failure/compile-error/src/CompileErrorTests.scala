package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object CompileErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("foo.scalaVersion")

      assert(!res.isSuccess)

      res.assertContainsLines(
        "[error] bar.mill:15:9",
        "println(doesntExist)",
        "        ^^^^^^^^^^^",
        "Not found: doesntExist"
      )

      res.assertContainsLines(
        "[error] qux.mill:4:34",
        "def myOtherMsg = myMsg.substring(\"0\")",
        "                                 ^^^",
        "Found:    (\"0\" : String)",
        "Required: Int"
      )

      res.assertContainsLines(
        "[error] build.mill:11:5",
        "foo.noSuchMethod",
        "    ^^^^^^^^^^^^"
      )
      assert(res.err.contains("""value noSuchMethod is not a member"""))
    }
  }
}
