package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlHeaderHelperTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval("version")

      assert(res.isSuccess == false)
      assert(res.err.contains("helper.mill:1:1"))
      assert(res.err.contains("//| mill-version: 1.0.0-RC1"))
      assert(res.err.contains("^"))
      assert(res.err.contains(
        "YAML header can only be defined in the `build.mill` file, not `helper.mill`"
      ))
    }
  }
}
