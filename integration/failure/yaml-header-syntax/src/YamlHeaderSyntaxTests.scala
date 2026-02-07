package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object YamlHeaderSyntaxTests extends UtestIntegrationTestSuite {
  override def cleanupProcessIdFile =
    false // process never launches due to yaml header syntax error
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res = eval(("resolve", "_"))

      assert(res.isSuccess == false)
      val expectedError = "Failed parsing build header:"
      assert(res.err.contains(expectedError))
      // make sure we truncate the exception to the relevant bits
      assert(res.err.linesIterator.toList.length < 30)
    }
  }
}
