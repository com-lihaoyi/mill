package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object YamlHeaderValueTests extends UtestIntegrationTestSuite {
  override def cleanupProcessIdFile =
    false // process never launches due to yaml header syntax error
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res = eval("version")

      assert(res.isSuccess == false)
      res.assertContainsLines(
        "[error] build.mill:1:14",
        "//| mvnDeps: lols",
        "^"
      )
      assert(
        res.err.contains("Failed de-serializing config override: expected sequence got string")
      )
      // make sure we truncate the exception to the relevant bits
      assert(res.err.linesIterator.toList.length < 20)
    }
  }
}
