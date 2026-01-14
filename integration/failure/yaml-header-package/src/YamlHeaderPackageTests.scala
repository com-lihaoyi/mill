package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object YamlHeaderPackageTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res = eval("version")

      assert(res.isSuccess == false)
      res.assertContainsLines(
        "[error] [error] package.mill:2:1",
        "//| mill-version: 1.0.0-RC1",
        "^"
      )
      assert(res.err.contains(
        "YAML header can only be defined in the `build.mill` file, not `package.mill`"
      ))
    }
  }
}
