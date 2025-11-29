package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlHeaderKeyTests extends UtestIntegrationTestSuite {
  override def cleanupProcessIdFile =
    false // process never launches due to yaml header syntax error
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval("version")

      assert(res.isSuccess == false)
      val expectedError =
        """
          |invalid build config in `mill-build/build.mill`: key "invalidKey" does not override any task
          |invalid build config in `mill-build/build.mill`: key "mvnDep" does not override any task, did you mean "mvnDeps"?
          |invalid build config in `mill-build/build.mill`: key "mill-jm-version" does not override any task, did you mean "mill-jvm-version"?
          |""".stripMargin.trim.replace("\r", "")

      assert(res.err.contains(expectedError))
      // make sure we truncate the exception to the relevant bits
      assert(res.err.linesIterator.toList.length < 20)
    }
  }
}
