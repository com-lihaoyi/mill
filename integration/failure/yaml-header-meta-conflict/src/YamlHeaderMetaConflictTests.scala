package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object YamlHeaderMetaConflictTests extends UtestIntegrationTestSuite {
  override def cleanupProcessIdFile =
    false // process never launches due to yaml header syntax error
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res = eval("version")

      assert(res.isSuccess == false)

      res.assertContainsLines(
        "[error] build.mill:1:5",
        "//| mvnDeps: []",
        "^"
      )
      assert(res.err.contains(
        "Build header config conflicts with task defined in mill-build/build.mill:7"
      ))
      // make sure we truncate the exception to the relevant bits
      assert(res.err.linesIterator.toList.length < 20)
    }
  }
}
