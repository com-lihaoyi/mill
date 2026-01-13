package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object YamlHeaderHelperTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res = eval("version")

      assert(res.isSuccess == false)
      res.assertContainsLines(
        "[error] generatedScriptSources [error] helper.mill:1:1",
        "//| mill-version: 1.0.0-RC1",
        "^"
      )
      assert(res.err.contains(
        "YAML header can only be defined in the `build.mill` file, not `helper.mill`"
      ))
    }
  }
}
