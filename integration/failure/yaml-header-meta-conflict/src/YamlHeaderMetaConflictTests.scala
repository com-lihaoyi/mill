package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlHeaderMetaConflictTests extends UtestIntegrationTestSuite {
  override def cleanupProcessIdFile =
    false // process never launches due to yaml header syntax error
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval("version")

      assert(res.isSuccess == false)

      assert(res.err.contains(
        "mvnDeps Build header config conflicts with task defined in mill-build/build.mill:7"
      ))
      // make sure we truncate the exception to the relevant bits
      assert(res.err.linesIterator.toList.length < 20)
    }
  }
}
