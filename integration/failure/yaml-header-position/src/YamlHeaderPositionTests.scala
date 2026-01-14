package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object YamlHeaderPositionTests extends UtestIntegrationTestSuite {
  override def cleanupProcessIdFile =
    false // process never launches due to yaml header syntax error
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res = eval("version")

      assert(res.isSuccess == false)
      res.assertContainsLines(
        "[error] build.mill:3:1",
        "//| mill-version: 1.0.0-RC1",
        "^"
      )
      assert(res.err.contains("YAML header comments can only occur at the start of the file"))
    }
  }
}
