package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlBuildHelperTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval("version")

      assert(res.isSuccess == false)
      val expectedError =
        "Invalid YAML header comment at helper.mill:0: //| mill-version: 1.0.0-RC1\n" +
          "YAML header can only be defined in the `build.mill` file, not `helper.mill`"
      assert(res.err.contains(expectedError))
    }
  }
}
