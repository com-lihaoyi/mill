package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlHeaderValueTests extends UtestIntegrationTestSuite {
  override def cleanupProcessIdFile =
    false // process never launches due to yaml header syntax error
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval("version")

      assert(res.isSuccess == false)
      assert(res.err.contains("[error] build.mill:1:14"))
      assert(res.err.contains("//| mvnDeps: lols"))
      assert(res.err.contains("             ^"))
      assert(
        res.err.contains("Failed de-serializing config override: expected sequence got string")
      )
      // make sure we truncate the exception to the relevant bits
      assert(res.err.linesIterator.toList.length < 20)
    }
  }
}
